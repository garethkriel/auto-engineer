package retry;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/** Runs a task, retrying retryable failures with exponential backoff. */
public final class Retrier {
    private final RetryPolicy policy;
    private final Sleeper sleeper;

    public Retrier(RetryPolicy policy, Sleeper sleeper) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Calls {@code task} until it succeeds.
     *
     * @throws RetryExhaustedException if all attempts failed with retryable exceptions
     * @throws InterruptedException    if interrupted while waiting (flag is restored)
     * @throws Exception               a non-retryable failure, immediately
     */
    public <T> T call(Callable<T> task, Predicate<Exception> retryable) throws Exception {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retryable, "retryable");
        Duration waited = Duration.ZERO;
        for (int attempt = 1; ; attempt++) {
            try {
                return task.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (!retryable.test(e)) {
                    throw e;
                }
                if (attempt >= policy.maxAttempts()) {
                    throw new RetryExhaustedException(attempt, waited, e);
                }
                Duration delay = policy.delayBefore(attempt);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                waited = waited.plus(delay);
            }
        }
    }

    /** Like {@link #call} but returns empty instead of throwing when attempts are exhausted. */
    public <T> Optional<T> attempt(Callable<T> task, Predicate<Exception> retryable) throws Exception {
        try {
            return Optional.ofNullable(call(task, retryable));
        } catch (RetryExhaustedException e) {
            return Optional.empty();
        }
    }
}
