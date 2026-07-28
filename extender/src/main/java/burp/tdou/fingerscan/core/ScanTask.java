package burp.tdou.fingerscan.core;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;

/**
 * 扫描任务 - 由 ScanStrategy 生成，由 RequestPipeline 执行
 */
public class ScanTask {

    public enum TaskType {
        /** 需要发送 HTTP 请求的主动扫描任务 → 走请求池 */
        HTTP_REQUEST,
        /** 只做指纹匹配的被动分析任务（不发请求）→ 走分析池 */
        PASSIVE_MATCH,
        /** Icon Hash 分析任务（不发请求）→ 走分析池 */
        ICON_HASH
    }

    private final String deduplicationKey;
    private final String from;
    private final TaskType type;
    private final HttpService service;
    private final byte[] requestBytes;
    private final HttpRequestResponse reqResp;

    private ScanTask(Builder builder) {
        this.deduplicationKey = builder.deduplicationKey;
        this.from = builder.from;
        this.type = builder.type;
        this.service = builder.service;
        this.requestBytes = builder.requestBytes;
        this.reqResp = builder.reqResp;
    }

    public String getDeduplicationKey() { return deduplicationKey; }
    public String getFrom() { return from; }
    public TaskType getType() { return type; }
    public HttpService getService() { return service; }
    public byte[] getRequestBytes() { return requestBytes; }
    public HttpRequestResponse getReqResp() { return reqResp; }
    public String getHost() { return service != null ? service.host() : ""; }

    /**
     * 是否为分析任务（不发 HTTP 请求）
     */
    public boolean isAnalysisOnly() {
        return type == TaskType.PASSIVE_MATCH || type == TaskType.ICON_HASH;
    }

    /**
     * 创建主动 HTTP 请求任务（走请求池，受 QPS 限制）
     */
    public static ScanTask httpRequest(HttpService service, byte[] requestBytes,
                                       String dedupKey, String from) {
        return new Builder()
                .type(TaskType.HTTP_REQUEST)
                .service(service)
                .requestBytes(requestBytes)
                .deduplicationKey(dedupKey)
                .from(from)
                .build();
    }

    /**
     * 创建被动指纹匹配任务（走分析池，不发请求）
     */
    public static ScanTask passiveMatch(HttpRequestResponse reqResp, HttpService service,
                                        String dedupKey, String from) {
        return new Builder()
                .type(TaskType.PASSIVE_MATCH)
                .reqResp(reqResp)
                .service(service)
                .deduplicationKey(dedupKey)
                .from(from)
                .build();
    }

    /**
     * 创建 Icon Hash 分析任务（走分析池，不发请求）
     */
    public static ScanTask iconHash(HttpRequestResponse reqResp, HttpService service,
                                    String dedupKey, String from) {
        return new Builder()
                .type(TaskType.ICON_HASH)
                .reqResp(reqResp)
                .service(service)
                .deduplicationKey(dedupKey)
                .from(from)
                .build();
    }

    public static class Builder {
        private String deduplicationKey;
        private String from;
        private TaskType type = TaskType.HTTP_REQUEST;
        private HttpService service;
        private byte[] requestBytes;
        private HttpRequestResponse reqResp;

        public Builder deduplicationKey(String key) { this.deduplicationKey = key; return this; }
        public Builder from(String from) { this.from = from; return this; }
        public Builder type(TaskType type) { this.type = type; return this; }
        public Builder service(HttpService service) { this.service = service; return this; }
        public Builder requestBytes(byte[] bytes) { this.requestBytes = bytes; return this; }
        public Builder reqResp(HttpRequestResponse rr) { this.reqResp = rr; return this; }

        public ScanTask build() { return new ScanTask(this); }
    }
}
