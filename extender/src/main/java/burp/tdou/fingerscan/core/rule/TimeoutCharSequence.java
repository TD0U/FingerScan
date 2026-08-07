package burp.tdou.fingerscan.core.rule;

/**
 * 包装 CharSequence：在 {@link #charAt(int)} 时检查截止时间。
 * Java {@link java.util.regex.Matcher} 回溯会频繁调用 charAt，从而可打断灾难性回溯。
 */
public final class TimeoutCharSequence implements CharSequence {

    private final CharSequence delegate;
    private final long deadlineNanos;
    private final String ruleName;
    private final long timeoutMs;
    /** 每 N 次 charAt 检查一次时钟，降低 nanoTime 开销 */
    private final int checkEvery;
    private int counter;

    public TimeoutCharSequence(CharSequence delegate, long deadlineNanos,
                               String ruleName, long timeoutMs) {
        this(delegate, deadlineNanos, ruleName, timeoutMs, 256);
    }

    public TimeoutCharSequence(CharSequence delegate, long deadlineNanos,
                               String ruleName, long timeoutMs, int checkEvery) {
        this.delegate = delegate;
        this.deadlineNanos = deadlineNanos;
        this.ruleName = ruleName != null ? ruleName : "?";
        this.timeoutMs = timeoutMs;
        this.checkEvery = Math.max(1, checkEvery);
    }

    private void check() {
        if ((++counter % checkEvery) == 0 && System.nanoTime() > deadlineNanos) {
            throw new MatchTimeoutException(ruleName, timeoutMs);
        }
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public char charAt(int index) {
        check();
        return delegate.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        // 子序列共享同一 deadline
        return new TimeoutCharSequence(delegate.subSequence(start, end),
                deadlineNanos, ruleName, timeoutMs, checkEvery);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
