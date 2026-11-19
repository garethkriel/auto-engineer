package ae.coord;

/** Source of time in milliseconds; injected so tests are deterministic. */
public interface Clock {
    long now();
}
