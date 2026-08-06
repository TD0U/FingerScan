package burp.tdou.fingerscan.core;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;

/**
 * 统一扫描请求模型
 * 所有来源（代理、手动发送、导入、重定向）的请求都包装为此对象
 */
public class ScanRequest {

    /** 请求来源常量 */
    public static final String FROM_PROXY = "Proxy";
    public static final String FROM_SEND = "Send";
    public static final String FROM_PROCESS = "Process";
    public static final String FROM_IMPORT = "Import";
    public static final String FROM_SCAN = "Scan";
    public static final String FROM_REDIRECT = "Redirect";
    /** 主动拉取 favicon（IconHash 策略声明后发起） */
    public static final String FROM_ICON_HASH = "IconHash";

    private final HttpRequestResponse httpReqResp;
    private final String from;
    private final String baseUrl;
    private final String path;
    private final String method;
    private final String host;
    private final HttpService service;
    private final String parentId;

    private ScanRequest(Builder builder) {
        this.httpReqResp = builder.httpReqResp;
        this.from = builder.from;
        this.baseUrl = builder.baseUrl;
        this.path = builder.path;
        this.method = builder.method;
        this.host = builder.host;
        this.service = builder.service;
        this.parentId = builder.parentId;
    }

    public HttpRequestResponse getHttpReqResp() {
        return httpReqResp;
    }

    public String getFrom() {
        return from;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getPath() {
        return path;
    }

    public String getMethod() {
        return method;
    }

    public String getHost() {
        return host;
    }

    public HttpService getService() {
        return service;
    }

    public String getParentId() {
        return parentId;
    }

    /**
     * 是否有响应数据（用于判断是否可做被动指纹识别）
     */
    public boolean hasResponse() {
        return httpReqResp != null
                && httpReqResp.response() != null
                && httpReqResp.response().toByteArray().length() > 0;
    }

    /**
     * 是否来自代理
     */
    public boolean isFromProxy() {
        return FROM_PROXY.equals(from);
    }

    /**
     * 是否为低频任务
     */
    public boolean isLowFrequency() {
        return from != null && (from.startsWith(FROM_PROXY)
                || from.startsWith(FROM_SEND)
                || from.startsWith(FROM_REDIRECT));
    }

    /**
     * 是否来自重定向
     */
    public boolean isFromRedirect() {
        return from != null && from.startsWith(FROM_REDIRECT);
    }

    /**
     * 从重定向结果创建新的 ScanRequest
     */
    public static ScanRequest redirect(ScanRequest original, HttpRequestResponse redirectReqResp,
                                       HttpService redirectService, String redirectPath, String parentDataId) {
        return new Builder()
                .httpReqResp(redirectReqResp)
                .from(FROM_REDIRECT + "(" + parentDataId + ")")
                .baseUrl(original.getBaseUrl())
                .path(redirectPath)
                .host(redirectService.host())
                .service(redirectService)
                .parentId(parentDataId)
                .build();
    }

    public static class Builder {
        private HttpRequestResponse httpReqResp;
        private String from;
        private String baseUrl;
        private String path;
        private String method;
        private String host;
        private HttpService service;
        private String parentId;

        public Builder httpReqResp(HttpRequestResponse httpReqResp) {
            this.httpReqResp = httpReqResp;
            return this;
        }

        public Builder from(String from) {
            this.from = from;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder service(HttpService service) {
            this.service = service;
            return this;
        }

        public Builder parentId(String parentId) {
            this.parentId = parentId;
            return this;
        }

        public ScanRequest build() {
            return new ScanRequest(this);
        }
    }
}
