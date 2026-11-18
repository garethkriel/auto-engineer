package ae.net;

import java.time.Duration;

/** Abstraction over sleeping so retry delays can be observed without waiting. */
@FunctionalInterface
public interface Sleeper {
    /** Sleeps for the given duration. */
    void sleep(Duration d) throws InterruptedException;

    /** Real sleeper backed by {@link Thread#sleep(Duration)}. */
    Sleeper SYSTEM = Thread::sleep;
}
