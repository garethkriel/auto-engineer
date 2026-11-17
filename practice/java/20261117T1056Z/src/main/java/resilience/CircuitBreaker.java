package resilience;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Thread-safe circuit breaker with CLOSED, OPEN and HALF_OPEN states and an injectable clock.
 *
 * <p>State changes happen under a short lock; the guarded action always runs outside it. Every
 * permitted call carries the generation it started in, and a result whose generation is no longer
 * current (the state moved on while it was running) is ignored.
 */
public final class CircuitBreaker {
    /** Breaker state. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Source of monotonic time in milliseconds. */
    @FunctionalInterface
    public interface Clock {
        long nowMillis();
    }

    private final int failureThreshold;
    private final long openMillis;
    private final int halfOpenSuccesses;
    private final Predicate<Throwable> countsAsFailure;
    private final Clock clock;

    private final Object lock = new Object();
    private State state = State.CLOSED;
    private long generation;
    private int failures;
    private int probeSuccesses;
    private boolean probeInFlight;
    private long openedAt;
    private long rejected;

    public CircuitBreaker(int failureThreshold, long openMillis, int halfOpenSuccesses) {
        this(failureThreshold, openMillis, halfOpenSuccesses, t -> true, System::currentTimeMillis);
    }

    public CircuitBreaker(int failureThreshold, long openMillis, int halfOpenSuccesses,
                          Predicate<Throwable> countsAsFailure, Clock clock) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (openMillis < 1) throw new IllegalArgumentException("openMillis must be >= 1");
        if (halfOpenSuccesses < 1) throw new IllegalArgumentException("halfOpenSuccesses must be >= 1");
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
        this.halfOpenSuccesses = halfOpenSuccesses;
        this.countsAsFailure = Objects.requireNonNull(countsAsFailure, "countsAsFailure");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Runs {@code action} if the breaker permits it; rethrows whatever the action throws. */
    public <T> T call(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        long gen = acquire();
        T result;
        try {
            result = action.get();
        } catch (Throwable t) {
            if (countsAsFailure.test(t)) {
                onFailure(gen);
            } else {
                onNeutral(gen);
            }
            throw t;
        }
        onSuccess(gen);
        return result;
    }

    /** Runnable variant of {@link #call(Supplier)}. */
    public void run(Runnable action) {
        Objects.requireNonNull(action, "action");
        call(() -> {
            action.run();
            return null;
        });
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public int consecutiveFailures() {
        synchronized (lock) {
            return failures;
        }
    }

    public long rejectedCount() {
        synchronized (lock) {
            return rejected;
        }
    }

    private long acquire() {
        synchronized (lock) {
            if (state == State.OPEN) {
                if (clock.nowMillis() - openedAt < openMillis) {
                    return reject("circuit open");
                }
                state = State.HALF_OPEN;
                generation++;
                probeSuccesses = 0;
                probeInFlight = false;
            }
            if (state == State.HALF_OPEN) {
                if (probeInFlight) {
                    return reject("probe in flight");
                }
                probeInFlight = true;
            }
            return generation;
        }
    }

    private long reject(String why) {
        rejected++;
        throw new CircuitOpenException(why);
    }

    private void onSuccess(long gen) {
        synchronized (lock) {
            if (gen != generation) return;
            if (state == State.CLOSED) {
                failures = 0;
            } else if (state == State.HALF_OPEN) {
                probeInFlight = false;
                if (++probeSuccesses >= halfOpenSuccesses) {
                    state = State.CLOSED;
                    generation++;
                    failures = 0;
                }
            }
        }
    }

    private void onFailure(long gen) {
        synchronized (lock) {
            if (gen != generation) return;
            if (state == State.CLOSED) {
                if (++failures >= failureThreshold) trip();
            } else if (state == State.HALF_OPEN) {
                trip();
            }
        }
    }

    private void onNeutral(long gen) {
        synchronized (lock) {
            if (gen == generation && state == State.HALF_OPEN) {
                probeInFlight = false;
            }
        }
    }

    private void trip() {
        state = State.OPEN;
        generation++;
        openedAt = clock.nowMillis();
        probeInFlight = false;
        probeSuccesses = 0;
    }
}
