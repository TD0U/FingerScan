package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.Cookie;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import burp.tdou.common.helper.DomainHelper;
import burp.tdou.common.helper.UIHelper;
import burp.tdou.common.log.Logger;
import burp.tdou.common.utils.*;
import burp.tdou.fingerscan.FingerScan;
import burp.tdou.fingerscan.bean.TaskData;
import burp.tdou.fingerscan.common.*;
import burp.tdou.fingerscan.common.Constants;
import burp.tdou.fingerscan.manager.WordlistManager;
import burp.tdou.fingerscan.ui.tab.DataBoardTab;
import burp.tdou.fingerscan.common.Config;
import burp.tdou.fingerscan.ui.tab.config.OtherTab;
import burp.tdou.fingerscan.ui.tab.config.RequestTab;
import burp.tdou.fingerscan.ui.widget.TaskTable;
import burp.tdou.fingerscan.ui.widget.payloadlist.PayloadItem;
import burp.tdou.fingerscan.ui.widget.payloadlist.PayloadRule;
import burp.tdou.fingerscan.ui.widget.payloadlist.ProcessingItem;
// v3.0 新架构
import burp.tdou.fingerscan.core.ScanOrchestrator;
import burp.tdou.fingerscan.core.ScanRequest;
import burp.tdou.fingerscan.core.ScanResult;
import burp.tdou.fingerscan.core.pipeline.RequestPipeline;
import burp.tdou.fingerscan.core.pipeline.ResultDispatcher;
import burp.tdou.fingerscan.core.rule.CompositeRuleEngine;
import burp.tdou.fingerscan.core.rule.YamlRuleEngine;
import burp.tdou.fingerscan.core.strategy.PassiveFingerprintStrategy;
import burp.tdou.fingerscan.core.strategy.PayloadProcessingStrategy;
import burp.tdou.fingerscan.core.strategy.RecursiveDirectoryScanStrategy;
import burp.tdou.fingerscan.core.strategy.IconHashStrategy;
import burp.tdou.fingerscan.config.YamlConfigLoader;
import burp.tdou.fingerscan.filter.FilterChain;
import burp.tdou.fingerscan.filter.HostFilter;
import burp.tdou.fingerscan.filter.MethodFilter;
import burp.tdou.fingerscan.filter.SuffixFilter;
import burp.tdou.fingerscan.core.iconhash.IconHashMatcher;
import burp.tdou.fingerscan.core.iconhash.IconHashRuleLoader;
import burp.tdou.fingerscan.core.iconhash.IconHashStore;
import burp.tdou.fingerscan.core.iconhash.FaviconRegistry;
import burp.tdou.fingerscan.core.path.PathStore;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;

import static burp.api.montoya.ui.editor.EditorOptions.READ_ONLY;

/**
 * 插件入口 (Montoya API)
 * <p>
 * Created by vaycore on 2022-08-07.
 */
public class BurpExtender implements BurpExtension,
        TaskTable.OnTaskTableEventListener, OnTabEventListener {

    private static final int TASK_THREAD_COUNT = 50;
    private static final int ANALYSIS_THREAD_COUNT = 10;

    // v3.0 新架构核心组件
    private ScanOrchestrator mOrchestrator;
    private YamlConfigLoader mYamlConfigLoader;
    private RecursiveDirectoryScanStrategy mRecursiveStrategy;
    private PayloadProcessingStrategy mPayloadStrategy;
    private IconHashStore mIconHashStore;
    private IconHashRuleLoader mIconHashRuleLoader;
    private PathStore mPathStore;

    // Burp 组件 (Montoya)
    private MontoyaApi mApi;
    private FingerScan mFingerScan;
    private DataBoardTab mDataBoardTab;
    private HttpRequestEditor mRequestTextEditor;
    private HttpResponseEditor mResponseTextEditor;
    private ExecutorService mRefreshMsgTask;
    private HttpRequestResponse mCurrentReqResp;
    private Timer mStatusRefresh;

    @Override
    public void initialize(MontoyaApi api) {
        this.mApi = api;
        initData();
        initView();
        initEvent();
        Logger.debug("register Extender ok! Log: %b", Constants.DEBUG);
    }

    private void initData() {
        this.mRefreshMsgTask = Executors.newSingleThreadExecutor();
        this.mApi.extension().setName(Constants.PLUGIN_NAME + " v" + Constants.PLUGIN_VERSION);
        // 初始化日志打印
        Logger.init(Constants.DEBUG, mApi.logging().output(), mApi.logging().error());
        // 初始化默认配置
        Config.init(getWorkDir());
        // 初始化域名辅助类
        DomainHelper.init("public_suffix_list.json");
        // v3.0: 初始化新架构
        initNewArchitecture();
        // 注册插件卸载监听器
        this.mApi.extension().registerUnloadingHandler(new ExtensionUnloadingHandler() {
            @Override
            public void extensionUnloaded() {
                onExtensionUnloaded();
            }
        });
    }

    /**
     * v3.0: 初始化新架构组件
     */
    private void initNewArchitecture() {
        try {
            // 1. 配置加载器（带缓存 + SafeConstructor）
            String yamlPath = Config.get("yaml_config_path");
            mYamlConfigLoader = new YamlConfigLoader(yamlPath);
            initDefaultFilterRules();

            // 2. 规则引擎（仅 YAML）
            YamlRuleEngine yamlEngine = new YamlRuleEngine(mYamlConfigLoader);
            CompositeRuleEngine ruleEngine = new CompositeRuleEngine()
                    .register(yamlEngine);

            // 2.5 Icon Hash 组件
            mIconHashRuleLoader = new IconHashRuleLoader(mYamlConfigLoader);
            mIconHashRuleLoader.loadRules();
            IconHashMatcher iconHashMatcher = new IconHashMatcher(mIconHashRuleLoader);
            String workDir = getWorkDir();
            if (workDir == null) {
                workDir = Config.getWorkDir();
            }
            mIconHashStore = new IconHashStore(workDir);
            mIconHashStore.init();

            // 2.6 路径收集组件（共享 IconHashStore 的 DB 连接）
            if (mIconHashStore.getConnection() != null) {
                mPathStore = new PathStore(mIconHashStore.getConnection());
                mPathStore.createTable();
            }

            // 3. 请求管道
            int qpsLimit = Config.getInt(Config.KEY_QPS_LIMIT);
            int qpsDelay = Config.getInt(Config.KEY_REQUEST_DELAY);
            RequestPipeline pipeline = new RequestPipeline(
                    mApi, ruleEngine,
                    TASK_THREAD_COUNT, ANALYSIS_THREAD_COUNT,
                    qpsLimit, qpsDelay,
                    iconHashMatcher, mIconHashStore,
                    mYamlConfigLoader);

            // 4. 过滤器链
            FaviconRegistry faviconRegistry = new FaviconRegistry();
            FilterChain filterChain = new FilterChain()
                    .add(new MethodFilter())
                    .add(new HostFilter())
                    .add(new SuffixFilter(faviconRegistry));

            // 5. 编排器
            mRecursiveStrategy = new RecursiveDirectoryScanStrategy(ruleEngine);
            mPayloadStrategy = new PayloadProcessingStrategy();
            mOrchestrator = new ScanOrchestrator(pipeline, filterChain);
            mOrchestrator.registerStrategy(new PassiveFingerprintStrategy());
            mOrchestrator.registerStrategy(new IconHashStrategy(faviconRegistry));
            mOrchestrator.registerStrategy(mRecursiveStrategy);
            mOrchestrator.registerStrategy(mPayloadStrategy);

            // 6. 注册结果消费者：DataBoardTab 数据展示
            pipeline.getDispatcher().register(this::onScanResult);

            Logger.debug("v3.0 新架构初始化成功: %d strategies, %d filters, %d engines",
                    mOrchestrator.getStrategyCount(),
                    filterChain.size(),
                    ruleEngine.getEngineCount());

        } catch (Exception e) {
            Logger.error("v3.0 架构初始化失败: " + e.getMessage());
        }
    }

    /**
     * v3.0: 结果消费者 — 将扫描结果展示到 DataBoardTab
     */
    private void onScanResult(ScanResult result) {
        if (result == null) {
            return;
        }
        // 所有请求都发到扫描记录
        if (mFingerScan.getDataPanel() != null) {
            mFingerScan.getDataPanel().addRecord(result);
        }
        // 收集一级路径（仅代理流量）
        if (mPathStore != null && result.getUrl() != null
                && ScanRequest.FROM_PROXY.equals(result.getFrom())) {
            String firstPath = PathStore.extractFirstPath(result.getUrl());
            if (firstPath != null && !"/".equals(firstPath)) {
                mPathStore.savePath(firstPath, result.getHost() != null ? result.getHost() : "");
            }
        }
        // 只有匹配到指纹的才展示到数据看板
        if (result.hasMatch() && mDataBoardTab != null) {
            TaskData data = new TaskData();
            data.setFrom(result.getFrom());
            data.setMethod(result.getMethod());
            data.setHost(result.getHost());
            data.setUrl(result.getUrl());
            data.setTitle(result.getTitle());
            data.setIp(result.getIp());
            data.setStatus(result.getStatus());
            data.setLength(result.getLength());
            data.setReqResp(result.getReqResp());
            StringBuilder fp = new StringBuilder();
            for (burp.tdou.fingerscan.core.rule.MatchResult mr : result.getMatchResults()) {
                if (fp.length() > 0) fp.append(", ");
                fp.append(mr.getRuleName());
            }
            data.setFingerprint(fp.toString());
            mDataBoardTab.getTaskTable().addTaskData(data);
            handleFollowRedirect(data);
        }
    }

    @SuppressWarnings("deprecation")
    private void initView() {
        mFingerScan = new FingerScan();
        mDataBoardTab = mFingerScan.getDataBoardTab();
        mFingerScan.getConfigPanel().setOnTabEventListener(this);
        // 将 DataBoardTab 传递给策略（读取 UI 开关状态）
        if (mRecursiveStrategy != null) {
            mRecursiveStrategy.setDataBoardTab(mDataBoardTab);
        }
        if (mPayloadStrategy != null) {
            mPayloadStrategy.setDataBoardTab(mDataBoardTab);
        }
        // 将页面添加到 BurpSuite (Montoya API)
        mApi.userInterface().registerSuiteTab(Constants.PLUGIN_NAME, mFingerScan);
        // 创建请求和响应控件 (Montoya API)
        mRequestTextEditor = mApi.userInterface().createHttpRequestEditor(READ_ONLY);
        mResponseTextEditor = mApi.userInterface().createHttpResponseEditor(READ_ONLY);
        mDataBoardTab.init(mRequestTextEditor.uiComponent(), mResponseTextEditor.uiComponent());
        mDataBoardTab.getTaskTable().setOnTaskTableEventListener(this);
        // 初始化扫描记录面板的请求/响应查看器
        if (mFingerScan.getDataPanel() != null) {
            mFingerScan.getDataPanel().initEditors(mApi);
        }
        // 初始化图标数据面板
        if (mIconHashStore != null) {
            mFingerScan.initIconDataPanel(mIconHashStore);
        }
        // 注册图标面板"转为规则"后的刷新回调
        if (mFingerScan.getIconDataPanel() != null && mIconHashRuleLoader != null) {
            mFingerScan.getIconDataPanel().setOnRuleAddedCallback(() -> {
                mYamlConfigLoader.invalidateCache();
                mIconHashRuleLoader.invalidate();
                mIconHashRuleLoader.loadRules();
                if (mFingerScan.getFingerprintPanel() != null) {
                    mFingerScan.getFingerprintPanel().loadIconHashRules();
                }
            });
        }
        // 初始化路径收集面板
        if (mPathStore != null) {
            mFingerScan.initPathCollectPanel(mPathStore);
        }
        // 注册重新加载回调，刷新运行时ICO规则
        if (mFingerScan.getFingerprintPanel() != null && mIconHashRuleLoader != null) {
            mFingerScan.getFingerprintPanel().setOnReloadCallback(() -> {
                mYamlConfigLoader.invalidateCache();
                mIconHashRuleLoader.invalidate();
                mIconHashRuleLoader.loadRules();
            });
        }
    }

    private void initEvent() {
        // 监听代理的包 (Montoya API - ProxyResponseHandler)
        mApi.proxy().registerResponseHandler(new ProxyResponseHandler() {
            @Override
            public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse interceptedResponse) {
                if (!mDataBoardTab.hasListenProxyMessage()) {
                    return ProxyResponseReceivedAction.continueWith(interceptedResponse);
                }
                HttpRequestResponse httpReqResp = HttpRequestResponse.httpRequestResponse(
                        interceptedResponse.initiatingRequest(),
                        interceptedResponse
                );
                submitToOrchestrator(httpReqResp, ScanRequest.FROM_PROXY);
                return ProxyResponseReceivedAction.continueWith(interceptedResponse);
            }

            @Override
            public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse interceptedResponse) {
                return ProxyResponseToBeSentAction.continueWith(interceptedResponse);
            }
        });
        // 注册菜单 (Montoya API)
        mApi.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                ArrayList<Component> items = new ArrayList<>();
                JMenuItem sendToFingerScanItem = new JMenuItem(L.get("send_to_plugin"));
                items.add(sendToFingerScanItem);
                sendToFingerScanItem.addActionListener((e) -> new Thread(() -> {
                    List<HttpRequestResponse> messages = event.selectedRequestResponses();
                    for (HttpRequestResponse httpReqResp : messages) {
                        submitToOrchestrator(httpReqResp, ScanRequest.FROM_SEND);
                        if (mOrchestrator != null && mOrchestrator.getPipeline().isShutdown()) {
                            Logger.debug("sendToPlugin: pipeline is shutdown");
                            return;
                        }
                    }
                }).start());
                return items;
            }
        });
        // 状态栏刷新定时器（使用新架构的计数器）
        mStatusRefresh = new Timer(1000, e -> {
            if (mDataBoardTab == null || mOrchestrator == null) {
                return;
            }
            RequestPipeline pipeline = mOrchestrator.getPipeline();
            mDataBoardTab.refreshTaskStatus(pipeline.getRequestOverCount(), pipeline.getRequestCommitCount());
            mDataBoardTab.refreshTaskHistoryStatus();
        });
        mStatusRefresh.start();
    }

    /**
     * 初始化默认全局过滤规则（仅当 Filter_List 为空时）
     */
    private void initDefaultFilterRules() {
        if (mYamlConfigLoader == null) return;
        List<Map<String, Object>> existing = mYamlConfigLoader.getFilterRules();
        if (!existing.isEmpty()) return;

        List<Map<String, Object>> defaults = new ArrayList<>();
        Map<String, Object> rule1 = new HashMap<>();
        rule1.put("name", "Response Body 404");
        rule1.put("re", "\"status\":404|\"code\":404");
        rule1.put("loaded", true);
        defaults.add(rule1);

        Map<String, Object> rule2 = new HashMap<>();
        rule2.put("name", "Replay Attack Detection");
        rule2.put("re", "\"msg\":\"检测到重放攻击!\"");
        rule2.put("loaded", true);
        defaults.add(rule2);

        Map<String, Object> config = mYamlConfigLoader.readConfig();
        config.put("Filter_List", defaults);
        mYamlConfigLoader.writeConfig(config);
        Logger.debug("Initialized default filter rules: %d rules", defaults.size());
    }

    /**
     * 获取工作目录路径
     */
    private String getWorkDir() {
        String extFilename = mApi.extension().filename();
        if (extFilename == null || extFilename.isEmpty()) {
            return null;
        }
        String workDir = Paths.get(extFilename)
                .getParent().toString() + File.separator + "FingerScan" + File.separator;
        if (FileUtils.isDir(workDir)) {
            return workDir;
        }
        return null;
    }

    /**
     * v3.0: 统一提交扫描请求到编排器
     */
    private void submitToOrchestrator(HttpRequestResponse httpReqResp, String from) {
        if (mOrchestrator == null || httpReqResp == null) {
            return;
        }

        HttpRequest request = httpReqResp.request();
        if (request == null) {
            return;
        }

        HttpService service = request.httpService();
        if (service == null && httpReqResp.httpService() != null) {
            service = httpReqResp.httpService();
        }
        if (service == null) {
            return;
        }

        String method = request.method();
        String host = service.host();
        String path = request.path();
        String protocol = service.secure() ? "https" : "http";
        int port = service.port();
        String baseUrl;
        if (Utils.isIgnorePort(port)) {
            baseUrl = protocol + "://" + host;
        } else {
            baseUrl = protocol + "://" + host + ":" + port;
        }

        ScanRequest scanRequest = new ScanRequest.Builder()
                .httpReqResp(httpReqResp)
                .from(from)
                .baseUrl(baseUrl)
                .path(path)
                .method(method)
                .host(host)
                .service(service)
                .build();

        mOrchestrator.submit(scanRequest);
    }

    /**
     * Host 匹配（保留给 handleFollowRedirect 使用）
     */
    private static boolean matchHost(String host, String rule) {
        if (StringUtils.isEmpty(host)) {
            return StringUtils.isEmpty(rule);
        }
        if (rule.equals("*")) {
            return true;
        }
        if (!rule.contains("*")) {
            return host.equals(rule);
        }
        String ruleValue = rule.replace("*", "");
        if (rule.startsWith("*") && rule.endsWith("*")) {
            return host.contains(ruleValue);
        } else if (rule.startsWith("*")) {
            return host.endsWith(ruleValue);
        } else if (rule.endsWith("*")) {
            return host.startsWith(ruleValue);
        } else {
            String[] split = rule.split("\\*");
            return host.startsWith(split[0]) && host.endsWith(split[1]);
        }
    }

    /**
     * 处理跟随重定向
     */
    private void handleFollowRedirect(TaskData data) {
        // 如果未启用"跟随重定向"功能，不继续执行
        if (!Config.getBoolean(Config.KEY_FOLLOW_REDIRECT)) {
            return;
        }
        int status = data.getStatus();
        // 检测 30x 状态码
        if (status < 300 || status >= 400) {
            return;
        }
        // 如果线程中断，不继续往下执行
        if (Thread.currentThread().isInterrupted()) {
            Logger.debug("handleFollowRedirect: thread pool is shutdown, intercept data id: %s", data.getId());
            return;
        }
        // 解析响应头的 Location 值
        HttpRequestResponse reqResp = (HttpRequestResponse) data.getReqResp();
        if (reqResp == null || reqResp.response() == null) {
            return;
        }
        HttpResponse response = reqResp.response();
        String location = getLocationByResponse(response);
        if (location == null) {
            return;
        }
        // 如果启用了 Cookie 跟随，获取响应头中的 Cookie 值
        List<String> cookies = null;
        if (Config.getBoolean(Config.KEY_REDIRECT_COOKIES_FOLLOW)) {
            cookies = getCookieByResponse(response);
        }
        String reqHost = data.getHost();
        String reqPath = data.getUrl();
        try {
            HttpRequestResponse redirectReqResp;
            // Extract headers from request
            HttpRequest origRequest = reqResp.request();
            List<String> headers = new ArrayList<>();
            if (origRequest != null) {
                // Parse headers from raw request bytes
                byte[] reqBytes = origRequest.toByteArray().getBytes();
                String reqStr = new String(reqBytes);
                int headerEnd = reqStr.indexOf("\r\n\r\n");
                if (headerEnd < 0) headerEnd = reqStr.length();
                String[] lines = reqStr.substring(0, headerEnd).split("\r\n");
                for (String line : lines) {
                    headers.add(line);
                }
            }
            // 兼容完整 Host 地址
            if (UrlUtils.isHTTP(reqPath)) {
                URL originUrl = UrlUtils.parseURL(reqPath);
                URL redirectUrl = UrlUtils.parseRedirectTargetURL(originUrl, location);
                HttpService service = reqResp.httpService() != null
                        ? reqResp.httpService()
                        : origRequest.httpService();
                redirectReqResp = HttpReqRespAdapter.from(service, redirectUrl.toString(), headers, cookies);
            } else {
                URL originUrl = UrlUtils.parseURL(reqHost + reqPath);
                URL redirectUrl = UrlUtils.parseRedirectTargetURL(originUrl, location);
                HttpService service = HttpReqRespAdapter.buildHttpServiceByURL(redirectUrl);
                redirectReqResp = HttpReqRespAdapter.from(service, UrlUtils.toPQF(redirectUrl), headers, cookies);
            }
            submitToOrchestrator(redirectReqResp, ScanRequest.FROM_REDIRECT + "(" + data.getId() + ")");
        } catch (IllegalArgumentException e) {
            Logger.error("Follow redirect error: " + e.getMessage());
        }
    }

    /**
     * 从 HttpResponse 获取响应头 Location 值
     */
    private String getLocationByResponse(HttpResponse response) {
        List<HttpHeader> headers = response.headers();
        for (HttpHeader header : headers) {
            if (header.name().toLowerCase().startsWith("location")) {
                return header.value();
            }
        }
        return null;
    }

    /**
     * 从 HttpResponse 获取响应头 Set-Cookie 值，并转换为请求头的 Cookie 值列表
     */
    private List<String> getCookieByResponse(HttpResponse response) {
        List<Cookie> respCookies = response.cookies();
        List<String> cookies = new ArrayList<>();
        for (Cookie cookie : respCookies) {
            String name = cookie.name();
            String value = cookie.value();
            cookies.add(String.format("%s=%s", name, value));
        }
        return cookies;
    }

    @Override
    public void onChangeSelection(TaskData data) {
        // 如果 data 为空，表示执行了清空历史记录操作
        if (data == null) {
            onClearHistory();
            return;
        }
        mCurrentReqResp = (HttpRequestResponse) data.getReqResp();
        // 加载请求、响应数据包
        String hintText = L.get("message_editor_loading");
        HttpRequest hintReq = HttpRequest.httpRequest(hintText);
        HttpResponse hintResp = HttpResponse.httpResponse(hintText);
        mRequestTextEditor.setRequest(hintReq);
        mResponseTextEditor.setResponse(hintResp);
        mRefreshMsgTask.execute(this::refreshReqRespMessage);
    }

    /**
     * 清空历史记录
     */
    private void onClearHistory() {
        mCurrentReqResp = null;
        // v3.0: 通过管道清理
        if (mOrchestrator != null) {
            mOrchestrator.clearHistory();
        }
        mRequestTextEditor.setRequest(HttpRequest.httpRequest(""));
        mResponseTextEditor.setResponse(HttpResponse.httpResponse(""));
    }

    /**
     * 刷新请求响应信息
     */
    private void refreshReqRespMessage() {
        if (mCurrentReqResp == null) {
            mRequestTextEditor.setRequest(HttpRequest.httpRequest(""));
            mResponseTextEditor.setResponse(HttpResponse.httpResponse(""));
            return;
        }

        HttpRequest request = mCurrentReqResp.request();
        HttpResponse response = mCurrentReqResp.response();

        // 检测是否超过配置的显示长度限制
        int maxLength = Config.getInt(Config.KEY_MAX_DISPLAY_LENGTH);
        if (request != null && maxLength >= 100000 && request.toByteArray().length() >= maxLength) {
            String hint = L.get("message_editor_request_length_limit_hint");
            request = HttpRequest.httpRequest(hint);
        }
        if (response != null && maxLength >= 100000 && response.toByteArray().length() >= maxLength) {
            String hint = L.get("message_editor_response_length_limit_hint");
            response = HttpResponse.httpResponse(hint);
        }

        mRequestTextEditor.setRequest(request != null ? request : HttpRequest.httpRequest(""));
        mResponseTextEditor.setResponse(response != null ? response : HttpResponse.httpResponse(""));
    }

    @Override
    public void onSendToRepeater(ArrayList<TaskData> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (TaskData data : list) {
            if (data.getReqResp() == null) {
                continue;
            }
            HttpRequestResponse rr = (HttpRequestResponse) data.getReqResp();
            HttpRequest req = rr.request();
            if (req == null) {
                continue;
            }
            try {
                mApi.repeater().sendToRepeater(req);
            } catch (Exception e) {
                Logger.debug(e.getMessage());
            }
        }
    }

    @Override
    public byte[] getBodyByTaskData(TaskData data) {
        if (data == null || data.getReqResp() == null) {
            return new byte[]{};
        }
        HttpRequestResponse rr = (HttpRequestResponse) data.getReqResp();
        HttpResponse response = rr.response();
        if (response == null || response.toByteArray().length() == 0) {
            return new byte[]{};
        }
        return response.body().getBytes();
    }

    @Override
    public void addHostToBlocklist(ArrayList<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return;
        }
        List<String> list = WordlistManager.getList(WordlistManager.KEY_HOST_BLOCKLIST);
        for (String host : hosts) {
            if (!list.contains(host)) {
                list.add(host);
            }
        }
        WordlistManager.putList(WordlistManager.KEY_HOST_BLOCKLIST, list);
        mFingerScan.getConfigPanel().refreshHostTab();
    }

    @Override
    public void onTabEventMethod(String action, Object... params) {
        switch (action) {
            case RequestTab.EVENT_QPS_LIMIT:
            case RequestTab.EVENT_REQUEST_DELAY:
                // v3.0: 更新管道的 QPS 配置
                if (mOrchestrator != null) {
                    int limit = Config.getInt(Config.KEY_QPS_LIMIT);
                    int delay = Config.getInt(Config.KEY_REQUEST_DELAY);
                    mOrchestrator.getPipeline().updateQps(limit, delay);
                    Logger.debug("Event: QPS updated, limit=%d, delay=%d", limit, delay);
                }
                break;
            case OtherTab.EVENT_UNLOAD_PLUGIN:
                mApi.extension().unload();
                break;
            case DataBoardTab.EVENT_IMPORT_URL:
                importUrl((List<?>) params[0]);
                break;
            case DataBoardTab.EVENT_STOP_TASK:
                stopAllTask();
                break;
        }
    }

    /**
     * 导入 URL
     *
     * @param list URL 列表
     */
    private void importUrl(List<?> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        new Thread(() -> {
            for (Object item : list) {
                try {
                    String url = String.valueOf(item);
                    HttpRequestResponse httpReqResp = HttpReqRespAdapter.from(url);
                    submitToOrchestrator(httpReqResp, ScanRequest.FROM_IMPORT);
                } catch (IllegalArgumentException e) {
                    Logger.error("Import error: " + e.getMessage());
                }
                if (mOrchestrator != null && mOrchestrator.getPipeline().isShutdown()) {
                    Logger.debug("importUrl: pipeline is shutdown");
                    return;
                }
            }
        }).start();
    }

    /**
     * 停止扫描中的所有任务
     */
    private void stopAllTask() {
        if (mOrchestrator != null) {
            RequestPipeline.StopResult result = mOrchestrator.stopAll();
            Logger.info("Stopped %d tasks", result.stoppedTasks);
        }
        UIHelper.showTipsDialog(L.get("stop_task_tips"));
    }

    /**
     * 插件卸载回调
     */
    private void onExtensionUnloaded() {
        // 停止状态栏刷新
        mStatusRefresh.stop();

        // v3.0: 通过管道统一关闭
        if (mIconHashStore != null) {
            mIconHashStore.close();
        }
        if (mOrchestrator != null) {
            RequestPipeline.ShutdownResult result = mOrchestrator.shutdown();
            Logger.info("Close: request pool %d, analysis pool %d, dedup %d, timeout %d",
                    result.requestCount, result.analysisCount, result.dedupCount, result.timeoutCount);
        }

        // 清除任务列表
        int count = 0;
        if (mDataBoardTab != null) {
            TaskTable taskTable = mDataBoardTab.getTaskTable();
            if (taskTable != null) {
                count = taskTable.getTaskCount();
                taskTable.clearAll();
            }
            mDataBoardTab.closeImportUrlWindow();
        }
        Logger.info("Clear: task list completed. Total %d records.", count);

        Logger.info(Constants.UNLOAD_BANNER);
    }
}
