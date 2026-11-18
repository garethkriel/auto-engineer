package ae.net;

import java.time.Duration;
import java.util.Objects;

/**
 * Retry settings: attempt limit, exponential backoff (base * 2^(n-1), capped) and a
 * budget for the total time spent sleeping between attempts.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Duration totalBudget) {

    public RetryPolicy {
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        Objects.requireNonNull(totalBudget, "totalBudget");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (baseDelay.isZero() || baseDelay.isNegative()) throw new IllegalArgumentException("baseDelay must be > 0");
        if (maxDelay.compareTo(baseDelay) < 0) throw new IllegalArgumentException("maxDelay must be >= baseDelay");
        if (totalBudget.isNegative()) throw new IllegalArgumentException("totalBudget must be >= 0");
    }

    /** Backoff delay after {@code failedAttempts} failures (1-based), capped at maxDelay, overflow-safe. */
    public Duration delayFor(int failedAttempts) {
        if (failedAttempts < 1) throw new IllegalArgumentException("failedAttempts must be >= 1");
        Duration d = baseDelay;
        for (int i = 1; i < failedAttempts; i++) {
            if (d.compareTo(maxDelay) >= 0) return maxDelay;
            d = d.multipliedBy(2);
        }
        return d.compareTo(maxDelay) > 0 ? maxDelay : d;
    }
}
