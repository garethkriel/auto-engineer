package ae.jobs;

/**
 * Exponential backoff: the delay after failed attempt n (1-based) is
 * {@code min(maxDelayMs, baseDelayMs * 2^(n-1))}. A job dies once {@code maxAttempts} attempts failed.
 */
public record RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (baseDelayMs < 1) {
            throw new IllegalArgumentException("baseDelayMs must be >= 1 so a retry is never due immediately");
        }
        if (maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("maxDelayMs must be >= baseDelayMs");
        }
    }

    /** Delay before the next attempt after {@code failedAttempt} attempts have failed. */
    public long delayAfter(int failedAttempt) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("failedAttempt must be >= 1");
        }
        int shift = failedAttempt - 1;
        if (shift >= 62 || baseDelayMs > (maxDelayMs >> shift)) {
            return maxDelayMs;
        }
        return baseDelayMs << shift;
    }
}
