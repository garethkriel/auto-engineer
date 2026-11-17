package batch;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Minimal delayed-task abstraction so time can be controlled in tests. */
public interface Scheduler {
    /** Runs {@code task} once after {@code delay}; the returned action cancels it (best effort). */
    Runnable schedule(Runnable task, Duration delay);

    /** Adapts a real {@link ScheduledExecutorService}. */
    static Scheduler of(ScheduledExecutorService service) {
        Objects.requireNonNull(service, "service");
        return (task, delay) -> {
            ScheduledFuture<?> f = service.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> f.cancel(false);
        };
    }
}
