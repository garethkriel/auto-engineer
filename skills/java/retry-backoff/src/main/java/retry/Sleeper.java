package retry;

import java.time.Duration;

/** Abstraction over waiting so tests need not really sleep. */
@FunctionalInterface
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
}
