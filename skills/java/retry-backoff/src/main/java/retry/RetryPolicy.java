package retry;

import java.time.Duration;
import java.util.Objects;

/**
 * Exponential backoff configuration.
 *
 * @param maxAttempts  total number of calls allowed (first call plus retries), at least 1
 * @param initialDelay delay before the first retry
 * @param multiplier   growth factor between retries, at least 1
 * @param maxDelay     upper bound for any single delay
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, int multiplier, Duration maxDelay) {

    public RetryPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= initialDelay");
        }
    }

    /**
     * Delay to wait before retry number {@code attempt} (1 is the first retry).
     * Computed iteratively and capped at {@link #maxDelay()}, so it never overflows.
     */
    public Duration delayBefore(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        Duration delay = initialDelay;
        for (int i = 1; i < attempt; i++) {
            if (delay.compareTo(maxDelay) >= 0 || multiplier == 1) {
                break;
            }
            try {
                delay = delay.multipliedBy(multiplier);
            } catch (ArithmeticException overflow) {
                return maxDelay;
            }
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
