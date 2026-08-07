package burp.tdou.fingerscan.core.rule;

/**
 * 单条规则 Matcher.find 超过时限时抛出（由 {@link TimeoutCharSequence} 在 charAt 中触发）。
 */
public final class MatchTimeoutException extends RuntimeException {

    private final String ruleName;
    private final long timeoutMs;

    public MatchTimeoutException(String ruleName, long timeoutMs) {
        super("regex match timeout: rule=" + ruleName + ", limitMs=" + timeoutMs);
        this.ruleName = ruleName;
        this.timeoutMs = timeoutMs;
    }

    public String getRuleName() {
        return ruleName;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
