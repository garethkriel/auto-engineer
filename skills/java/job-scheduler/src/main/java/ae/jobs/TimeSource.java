package ae.jobs;

/** Source of the current time in milliseconds; injected so tests never sleep. */
@FunctionalInterface
public interface TimeSource {
    long now();
}
