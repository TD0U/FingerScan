package burp.tdou.fingerscan.core.pipeline;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.tdou.common.log.Logger;
import burp.tdou.common.utils.HtmlUtils;
import burp.tdou.common.utils.IPUtils;
import burp.tdou.common.utils.Utils;
import burp.tdou.fingerscan.common.Config;
import burp.tdou.fingerscan.core.ScanResult;
import burp.tdou.fingerscan.core.ScanTask;
import burp.tdou.fingerscan.core.rule.MatchResult;
import burp.tdou.fingerscan.core.rule.RuleEngine;
import burp.tdou.fingerscan.config.YamlConfigLoader;
import burp.tdou.fingerscan.core.iconhash.IconHashMatcher;
import burp.tdou.fingerscan.core.iconhash.IconHashStore;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 统一请求管道
 *
 * 两个线程池按职责划分：
 * - requestPool (请求池): 所有需要发 HTTP 请求的任务，受 QPS 限制
 * - analysisPool (分析池): 被动指纹匹配等纯分析任务，不发请求，不限速
 */
public class RequestPipeline {

    private final DeduplicateFilter dedup;
    private final QpsLimiter qpsLimiter;
    private final TimeoutTracker timeoutTracker;
    private final RequestExecutor executor;
    private final RetryPolicy retryPolicy;
    private final ResultDispatcher dispatcher;
    private final RuleEngine ruleEngine;
    private final MontoyaApi api;
    private final IconHashMatcher iconHashMatcher;
    private final IconHashStore iconHashStore;
    private final YamlConfigLoader configLoader;

    private volatile ExecutorService requestPool;
    private volatile ExecutorService analysisPool;

    private final AtomicInteger requestCommitCount = new AtomicInteger(0);
    private final AtomicInteger requestOverCount = new AtomicInteger(0);
    private final AtomicInteger analysisCount = new AtomicInteger(0);

    private final int requestThreadCount;
    private final int analysisThreadCount;

    public RequestPipeline(MontoyaApi api, RuleEngine ruleEngine,
                           int requestThreadCount, int analysisThreadCount,
                           int qpsLimit, int qpsDelay,
                           IconHashMatcher iconHashMatcher, IconHashStore iconHashStore,
                           YamlConfigLoader configLoader) {
        this.api = api;
        this.ruleEngine = ruleEngine;
        this.requestThreadCount = requestThreadCount;
        this.analysisThreadCount = analysisThreadCount;
        this.iconHashMatcher = iconHashMatcher;
        this.iconHashStore = iconHashStore;
        this.configLoader = configLoader;

        this.dedup = new DeduplicateFilter();
        this.qpsLimiter = new QpsLimiter(qpsLimit, qpsDelay);
        this.timeoutTracker = new TimeoutTracker();
        this.executor = new RequestExecutor(api);
        this.retryPolicy = new RetryPolicy(executor, timeoutTracker);
        this.dispatcher = new ResultDispatcher();

        this.requestPool = Executors.newFixedThreadPool(requestThreadCount);
        this.analysisPool = Executors.newFixedThreadPool(analysisThreadCount);
    }

    /**
     * 提交扫描任务
     */
    public void submit(ScanTask task) {
        if (dedup.isDuplicate(task.getDeduplicationKey())) {
            return;
        }

        if (task.getType() == ScanTask.TaskType.ICON_HASH) {
            submitIconHashAnalysis(task);
        } else if (task.isAnalysisOnly()) {
            submitAnalysis(task);
        } else {
            submitRequest(task);
        }
    }

    /**
     * 分析任务：不发 HTTP 请求，只做指纹匹配
     */
    private void submitAnalysis(ScanTask task) {
        ExecutorService pool = analysisPool;
        if (pool.isShutdown()) {
            dedup.remove(task.getDeduplicationKey());
            return;
        }

        pool.execute(() -> {
            try {
                String requestPath = extractRequestPath(task.getReqResp());
                List<MatchResult> matches = ruleEngine.match(
                        task.getExistingRequest(), task.getExistingResponse(), requestPath);

                // 无论是否匹配到指纹，都构建完整结果用于扫描记录
                ScanResult.Builder builder = new ScanResult.Builder()
                        .task(task)
                        .from(task.getFrom())
                        .matchResults(matches);

                fillResultFromReqResp(builder, task);
                dispatcher.dispatch(builder.build());
                analysisCount.incrementAndGet();
            } catch (Exception e) {
                Logger.error("Analysis error: %s", e.getMessage());
            }
        });
    }

    /**
     * Icon Hash 分析任务：计算 favicon hash 并匹配指纹 + 存储到 SQLite
     */
    private void submitIconHashAnalysis(ScanTask task) {
        ExecutorService pool = analysisPool;
        if (pool.isShutdown()) {
            dedup.remove(task.getDeduplicationKey());
            return;
        }

        pool.execute(() -> {
            try {
                byte[] respBytes = task.getExistingResponse();
                if (respBytes == null || respBytes.length == 0) {
                    return;
                }

                // 全局响应过滤器：匹配到过滤规则则跳过 icon hash 识别
                if (configLoader != null && configLoader.isResponseFiltered(respBytes)) {
                    return;
                }

                // Parse response to extract body - use raw byte parsing instead of helpers
                String respStr = new String(respBytes);

                // Skip non-2xx responses (e.g. 404 error pages)
                int firstLineEnd = respStr.indexOf("\r\n");
                if (firstLineEnd > 0) {
                    int statusCode = parseStatusCode(respStr.substring(0, firstLineEnd));
                    if (statusCode < 200 || statusCode >= 300) {
                        return;
                    }
                }

                int bodyOffset = respStr.indexOf("\r\n\r\n");
                if (bodyOffset < 0) {
                    return;
                }
                bodyOffset += 4;
                if (bodyOffset >= respBytes.length) {
                    return;
                }
                byte[] body = new byte[respBytes.length - bodyOffset];
                System.arraycopy(respBytes, bodyOffset, body, 0, body.length);

                List<MatchResult> matches = iconHashMatcher.match(body);

                // Extract content-type from headers
                String contentType = "";
                String headerSection = respStr.substring(0, bodyOffset - 4);
                String[] headerLines = headerSection.split("\r\n");
                for (String header : headerLines) {
                    if (header.toLowerCase().startsWith("content-type:")) {
                        contentType = header.substring(13).trim();
                        break;
                    }
                }

                String murmurHash = iconHashMatcher.computeMurmurHash(body);
                String md5 = iconHashMatcher.computeMd5(body);
                String matchName = matches.isEmpty() ? null :
                        matches.stream().map(MatchResult::getRuleName)
                               .collect(Collectors.joining(", "));

                HttpService service = task.getService();
                String host = service != null ? service.host() : "";
                String path = "";
                if (task.getReqResp() != null && task.getReqResp().request() != null) {
                    path = extractRequestPath(task.getReqResp().request());
                }

                iconHashStore.saveIcon(murmurHash, md5, body, contentType, matchName, host, path);

                List<MatchResult> allMatches = new ArrayList<>(matches);

                ScanResult.Builder builder = new ScanResult.Builder()
                        .task(task)
                        .from(task.getFrom())
                        .matchResults(allMatches);

                fillResultFromReqResp(builder, task);
                dispatcher.dispatch(builder.build());
                analysisCount.incrementAndGet();
            } catch (Exception e) {
                Logger.error("IconHash analysis error: %s", e.getMessage());
            }
        });
    }

    /**
     * 请求任务：发 HTTP 请求，受 QPS 限制
     */
    private void submitRequest(ScanTask task) {
        ExecutorService pool = requestPool;
        if (pool.isShutdown()) {
            dedup.remove(task.getDeduplicationKey());
            return;
        }

        try {
            pool.execute(() -> {
                try {
                    executeHttpTask(task);
                } finally {
                    requestOverCount.incrementAndGet();
                }
            });
            requestCommitCount.incrementAndGet();
        } catch (Exception e) {
            Logger.error("Pipeline submit error: %s", e.getMessage());
            dedup.remove(task.getDeduplicationKey());
        }
    }

    private void executeHttpTask(ScanTask task) {
        HttpService service = task.getService();
        String reqHost = buildReqHost(service);

        // QPS 限速
        try {
            qpsLimiter.acquire();
        } catch (InterruptedException e) {
            dedup.remove(task.getDeduplicationKey());
            return;
        }

        // 超时主机检查
        if (Config.getBoolean(Config.KEY_INTERCEPT_TIMEOUT_HOST)
                && timeoutTracker.isTimedOut(reqHost)) {
            ScanResult result = ScanResult.timeout(task);
            dispatcher.dispatch(result);
            return;
        }

        // 发请求（带重试）
        int retryCount = Config.getInt(Config.KEY_RETRY_COUNT);
        int retryInterval = Config.getInt(Config.KEY_RETRY_INTERVAL);
        HttpRequestResponse reqResp;
        try {
            reqResp = retryPolicy.executeWithRetry(
                    service, task.getRequestBytes(), retryCount, retryInterval, reqHost);
        } catch (InterruptedException e) {
            dedup.remove(task.getDeduplicationKey());
            return;
        }

        // 构建结果
        dispatcher.dispatch(buildHttpResult(task, reqResp, service));
    }

    private ScanResult buildHttpResult(ScanTask task, HttpRequestResponse reqResp, HttpService service) {
        String method = "";
        String reqHost = buildReqHost(service);
        String reqUrl = "";
        String title = "";
        String ip = findIpByHost(service.host());
        int status = -1;
        int length = -1;

        // Extract method and path from request
        HttpRequest request = reqResp.request();
        if (request != null) {
            method = request.method();
            reqUrl = extractRequestPath(request);
        }

        // Extract status and body length from response
        byte[] respBytes = null;
        HttpResponse response = reqResp.response();
        if (response != null) {
            respBytes = response.toByteArray().getBytes();
            if (respBytes != null && respBytes.length > 0) {
                status = response.statusCode();
                int bodyLen = response.body().length();
                length = bodyLen > 0 ? bodyLen : 0;
                title = HtmlUtils.findTitleByHtmlBody(respBytes);
            }
        }

        byte[] reqBytes = request != null ? request.toByteArray().getBytes() : null;
        List<MatchResult> matches = ruleEngine.match(reqBytes, respBytes, reqUrl);

        return new ScanResult.Builder()
                .task(task)
                .reqResp(reqResp)
                .from(task.getFrom())
                .method(method)
                .host(reqHost)
                .url(reqUrl)
                .title(title)
                .ip(ip)
                .status(status)
                .length(length)
                .matchResults(matches)
                .build();
    }

    /**
     * 从 HttpRequestResponse 填充 ScanResult 字段（用于被动分析任务）
     */
    private void fillResultFromReqResp(ScanResult.Builder builder, ScanTask task) {
        HttpRequestResponse reqResp = task.getReqResp();
        if (reqResp == null) return;

        builder.reqResp(reqResp);
        HttpService service = task.getService();
        if (service == null && reqResp.httpService() != null) {
            service = reqResp.httpService();
        }
        if (service != null) {
            builder.host(buildReqHost(service));
            builder.ip(findIpByHost(service.host()));
        }
        HttpRequest request = reqResp.request();
        if (request != null && request.toByteArray().length() > 0) {
            builder.method(request.method());
            builder.url(extractRequestPath(request));
        }
        HttpResponse response = reqResp.response();
        if (response != null && response.toByteArray().length() > 0) {
            builder.status(response.statusCode());
            int bodyLen = response.body().length();
            builder.length(bodyLen > 0 ? bodyLen : 0);
            builder.title(HtmlUtils.findTitleByHtmlBody(response.toByteArray().getBytes()));
        }
    }

    /**
     * 从 HttpRequest 中提取请求路径
     */
    private String extractRequestPath(HttpRequest request) {
        if (request == null) return "";
        // 优先使用 Montoya API 的 path()，返回纯路径（如 /swagger-ui.html）
        String path = request.path();
        if (path != null && !path.isEmpty()) {
            return path;
        }
        // 降级：从原始请求行解析
        String reqStr = request.toString();
        int lineEnd = reqStr.indexOf("\r\n");
        if (lineEnd < 0) lineEnd = reqStr.indexOf("\n");
        if (lineEnd < 0) return "";
        String reqLine = reqStr.substring(0, lineEnd);
        int start = reqLine.indexOf(' ');
        int end = reqLine.lastIndexOf(" HTTP/");
        if (start >= 0 && end > start) {
            return reqLine.substring(start + 1, end);
        }
        return "";
    }

    private String extractRequestPath(HttpRequestResponse reqResp) {
        if (reqResp == null || reqResp.request() == null) return "";
        return extractRequestPath(reqResp.request());
    }

    private static int parseStatusCode(String statusLine) {
        // "HTTP/1.1 404 Not Found" → 404
        try {
            int start = statusLine.indexOf(' ');
            if (start < 0) return 0;
            int end = statusLine.indexOf(' ', start + 1);
            if (end < 0) end = statusLine.length();
            return Integer.parseInt(statusLine.substring(start + 1, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // --- 辅助方法 ---

    private String buildReqHost(HttpService service) {
        String protocol = service.secure() ? "https" : "http";
        String host = service.host();
        int port = service.port();
        if (Utils.isIgnorePort(port)) return protocol + "://" + host;
        return protocol + "://" + host + ":" + port;
    }

    private String findIpByHost(String host) {
        if (IPUtils.hasIPv4(host)) return host;
        try { return InetAddress.getByName(host).getHostAddress(); }
        catch (UnknownHostException e) { return ""; }
    }

    // --- 公共 API ---

    public ResultDispatcher getDispatcher() { return dispatcher; }
    public DeduplicateFilter getDedup() { return dedup; }

    public int getRequestCommitCount() { return requestCommitCount.get(); }
    public int getRequestOverCount() { return requestOverCount.get(); }
    public int getAnalysisCount() { return analysisCount.get(); }

    public boolean isShutdown() {
        return requestPool.isShutdown();
    }

    public void updateQps(int limit, int delay) {
        qpsLimiter.update(limit, delay);
    }

    public StopResult stopAll() {
        List<Runnable> tasks = requestPool.shutdownNow();
        int stopped = tasks.size();
        requestPool = Executors.newFixedThreadPool(requestThreadCount);
        updateQps(qpsLimiter.getLimit(), qpsLimiter.getDelay());
        return new StopResult(stopped);
    }

    public ShutdownResult shutdown() {
        int reqCount = requestPool.shutdownNow().size();
        int anaCount = analysisPool.shutdownNow().size();
        int dedupCount = dedup.size();
        int timeoutCount = timeoutTracker.size();
        dedup.clear();
        timeoutTracker.clear();
        dispatcher.clear();
        return new ShutdownResult(reqCount, anaCount, dedupCount, timeoutCount);
    }

    public void clearHistory() {
        dedup.clear();
        timeoutTracker.clear();
    }

    public static class StopResult {
        public final int stoppedTasks;
        StopResult(int s) { this.stoppedTasks = s; }
    }

    public static class ShutdownResult {
        public final int requestCount;
        public final int analysisCount;
        public final int dedupCount;
        public final int timeoutCount;
        ShutdownResult(int r, int a, int d, int t) {
            this.requestCount = r; this.analysisCount = a;
            this.dedupCount = d; this.timeoutCount = t;
        }
    }
}
