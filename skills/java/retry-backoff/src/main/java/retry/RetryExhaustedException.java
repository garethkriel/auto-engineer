package retry;

import java.time.Duration;

/** Checked exception thrown when every allowed attempt failed with a retryable error. */
public class RetryExhaustedException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int attempts;
    private final Duration totalDelay;

    public RetryExhaustedException(int attempts, Duration totalDelay, Throwable lastFailure) {
        super("gave up after " + attempts + " attempts (waited " + totalDelay + ")", lastFailure);
        this.attempts = attempts;
        this.totalDelay = totalDelay;
    }

    public int attempts() {
        return attempts;
    }

    public Duration totalDelay() {
        return totalDelay;
    }
}
