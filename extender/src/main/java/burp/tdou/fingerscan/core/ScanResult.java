package burp.tdou.fingerscan.core;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.tdou.fingerscan.core.rule.MatchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统一扫描结果模型
 * 所有扫描产出（主动扫描响应、被动指纹匹配）都包装为此对象
 */
public class ScanResult {

    private final HttpRequestResponse reqResp;
    private final String from;
    private final String method;
    private final String host;
    private final String url;
    private final String title;
    private final String ip;
    private final int status;
    private final int length;
    private final List<MatchResult> matchResults;
    private final long timestamp;
    private final boolean timeout;

    private ScanResult(Builder builder) {
        this.reqResp = builder.reqResp;
        this.from = builder.from;
        this.method = builder.method;
        this.host = builder.host;
        this.url = builder.url;
        this.title = builder.title;
        this.ip = builder.ip;
        this.status = builder.status;
        this.length = builder.length;
        this.matchResults = builder.matchResults != null
                ? Collections.unmodifiableList(builder.matchResults)
                : Collections.emptyList();
        this.timestamp = System.currentTimeMillis();
        this.timeout = builder.timeout;
    }

    public HttpRequestResponse getReqResp() { return reqResp; }
    public String getFrom() { return from; }
    public String getMethod() { return method; }
    public String getHost() { return host; }
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getIp() { return ip; }
    public int getStatus() { return status; }
    public int getLength() { return length; }
    public List<MatchResult> getMatchResults() { return matchResults; }
    public long getTimestamp() { return timestamp; }
    public boolean isTimeout() { return timeout; }

    /**
     * 是否为重定向响应
     */
    public boolean isRedirect() {
        return status >= 300 && status < 400;
    }

    /**
     * 是否有指纹匹配结果
     */
    public boolean hasMatch() {
        return !matchResults.isEmpty();
    }

    /**
     * 创建超时结果
     */
    public static ScanResult timeout(ScanTask task) {
        return new Builder()
                .from(task.getFrom())
                .status(-1)
                .length(-1)
                .timeout(true)
                .build();
    }

    public static class Builder {
        private HttpRequestResponse reqResp;
        private String from;
        private String method;
        private String host;
        private String url;
        private String title;
        private String ip;
        private int status = -1;
        private int length = -1;
        private List<MatchResult> matchResults;
        private boolean timeout;

        public Builder reqResp(HttpRequestResponse rr) { this.reqResp = rr; return this; }
        public Builder from(String from) { this.from = from; return this; }
        public Builder method(String method) { this.method = method; return this; }
        public Builder host(String host) { this.host = host; return this; }
        public Builder url(String url) { this.url = url; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder ip(String ip) { this.ip = ip; return this; }
        public Builder status(int status) { this.status = status; return this; }
        public Builder length(int length) { this.length = length; return this; }
        public Builder matchResults(List<MatchResult> results) { this.matchResults = results; return this; }
        public Builder addMatchResult(MatchResult result) {
            if (this.matchResults == null) this.matchResults = new ArrayList<>();
            this.matchResults.add(result);
            return this;
        }
        public Builder timeout(boolean timeout) { this.timeout = timeout; return this; }

        public ScanResult build() {
            return new ScanResult(this);
        }
    }
}
