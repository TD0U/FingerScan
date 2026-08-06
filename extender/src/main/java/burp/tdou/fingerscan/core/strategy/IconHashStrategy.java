package burp.tdou.fingerscan.core.strategy;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.tdou.common.log.Logger;
import burp.tdou.common.utils.Utils;
import burp.tdou.fingerscan.core.ScanRequest;
import burp.tdou.fingerscan.core.ScanTask;
import burp.tdou.fingerscan.core.iconhash.FaviconDetector;
import burp.tdou.fingerscan.core.iconhash.FaviconLinkExtractor;
import burp.tdou.fingerscan.core.iconhash.FaviconRegistry;
import burp.tdou.fingerscan.core.pipeline.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Icon Hash 策略
 *
 * 1) HTML 响应：解析 &lt;link rel="icon"&gt;，注册路径，并<strong>主动拉取</strong>同 host 的 favicon
 *    （不依赖浏览器是否/何时请求该图标，避免与 HTML 处理的竞态）
 * 2) 已是 favicon 的代理流量：对已有响做被动 Icon Hash 分析
 */
public class IconHashStrategy implements ScanStrategy {

    private final FaviconRegistry faviconRegistry;

    public IconHashStrategy(FaviconRegistry faviconRegistry) {
        this.faviconRegistry = faviconRegistry;
    }

    @Override
    public boolean shouldApply(ScanRequest request) {
        if (!request.hasResponse()) {
            return false;
        }

        HttpRequestResponse reqResp = request.getHttpReqResp();
        HttpResponse response = reqResp.response();
        String contentType = extractContentType(response);

        // HTML：需要解析 link 并生成主动拉取任务
        if (FaviconDetector.isHtmlContentType(contentType)) {
            return true;
        }

        // 已识别的 favicon 流量：被动分析
        return faviconRegistry.isFavicon(request.getHost(), request.getPath())
                || FaviconDetector.isDefaultFaviconPath(request.getPath());
    }

    @Override
    public List<ScanTask> generateTasks(ScanRequest request) {
        if (!shouldApply(request)) {
            return Collections.emptyList();
        }

        HttpRequestResponse reqResp = request.getHttpReqResp();
        HttpResponse response = reqResp.response();
        String contentType = extractContentType(response);

        if (FaviconDetector.isHtmlContentType(contentType)) {
            return generateActiveFetchTasks(request, response);
        }

        // 被动：对当前 favicon 响应做 Icon Hash
        String faviconUrl = request.getHost() + request.getPath();
        String dedupKey = "iconhash:" + faviconUrl;
        List<ScanTask> tasks = new ArrayList<>(1);
        tasks.add(ScanTask.iconHash(reqResp, request.getService(), dedupKey, request.getFrom()));
        return tasks;
    }

    @Override
    public String getName() {
        return "IconHash";
    }

    /**
     * 从 HTML 解析 favicon 链接，注册到 registry，并对同 host 路径生成主动 GET 任务
     */
    private List<ScanTask> generateActiveFetchTasks(ScanRequest request, HttpResponse response) {
        List<FaviconLinkExtractor.FaviconLink> links = extractFaviconLinks(request, response);
        String pageHost = request.getHost();
        HttpService service = request.getService();
        if (service == null) {
            return Collections.emptyList();
        }

        // 保序去重
        Set<String> pathsToFetch = new LinkedHashSet<>();
        for (FaviconLinkExtractor.FaviconLink link : links) {
            String host = link.host != null ? link.host : pageHost;
            String path = link.path;
            if (path == null || path.isEmpty()) {
                continue;
            }
            // 注册（跨域也注册，便于后续被动流量命中）
            faviconRegistry.register(host, path);
            Logger.debug("IconHashStrategy: registered favicon %s for host %s", path, host);

            // 只主动拉取同 host，避免对第三方域名发包
            if (isSameHost(pageHost, host)) {
                pathsToFetch.add(path);
            }
        }

        if (pathsToFetch.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> originalHeaders = getOriginalHeaders(request);
        List<ScanTask> tasks = new ArrayList<>(pathsToFetch.size());
        for (String path : pathsToFetch) {
            byte[] requestBytes = buildFaviconRequest(originalHeaders, path, service);
            if (requestBytes == null) {
                continue;
            }
            String dedupKey = "iconhash:" + pageHost + path;
            tasks.add(ScanTask.httpRequest(service, requestBytes, dedupKey, ScanRequest.FROM_ICON_HASH));
            Logger.debug("IconHashStrategy: active fetch task for %s%s", pageHost, path);
        }
        return tasks;
    }

    private List<FaviconLinkExtractor.FaviconLink> extractFaviconLinks(ScanRequest request,
                                                                        HttpResponse response) {
        try {
            String body = response.bodyToString();
            if (body == null || body.isEmpty()) {
                return Collections.emptyList();
            }
            return FaviconLinkExtractor.extract(body, request.getPath());
        } catch (Exception e) {
            Logger.error("IconHashStrategy: failed to extract favicon links: %s", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static boolean isSameHost(String pageHost, String linkHost) {
        if (pageHost == null || linkHost == null) {
            return false;
        }
        return pageHost.equalsIgnoreCase(linkHost);
    }

    /**
     * 优先复用页面原始请求头构建 GET；失败则降级为最小请求
     */
    private byte[] buildFaviconRequest(List<String> originalHeaders, String path, HttpService service) {
        if (originalHeaders != null && !originalHeaders.isEmpty()) {
            byte[] built = RequestBuilder.buildScanRequest(
                    originalHeaders, path, service, false, false);
            if (built != null) {
                return built;
            }
        }
        return buildMinimalGet(path, service);
    }

    private static byte[] buildMinimalGet(String path, HttpService service) {
        String host = service.host();
        int port = service.port();
        if (!Utils.isIgnorePort(port)) {
            host = host + ":" + port;
        }
        String raw = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Accept: image/*,*/*;q=0.8\r\n"
                + "Connection: close\r\n\r\n";
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    private List<String> getOriginalHeaders(ScanRequest request) {
        if (request.getHttpReqResp() == null || request.getHttpReqResp().request() == null) {
            return Collections.emptyList();
        }
        try {
            byte[] reqBytes = request.getHttpReqResp().request().toByteArray().getBytes();
            String reqStr = new String(reqBytes, StandardCharsets.ISO_8859_1);
            int headerEnd = reqStr.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                headerEnd = reqStr.length();
            }
            String headerPart = reqStr.substring(0, headerEnd);
            String[] lines = headerPart.split("\r\n");
            List<String> headers = new ArrayList<>(lines.length);
            for (String line : lines) {
                if (!line.isEmpty()) {
                    headers.add(line);
                }
            }
            return headers;
        } catch (Exception e) {
            Logger.debug("IconHashStrategy: failed to parse original headers: %s", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String extractContentType(HttpResponse response) {
        if (response == null) {
            return "";
        }
        try {
            String ct = response.headerValue("Content-Type");
            return ct != null ? ct : "";
        } catch (Exception e) {
            return "";
        }
    }
}
