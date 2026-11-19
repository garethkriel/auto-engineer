package ae.coord;

/** A clock that only moves when told to. Not thread-safe by itself beyond volatile reads. */
public final class ManualClock implements Clock {
    private volatile long now;

    @Override
    public long now() {
        return now;
    }

    /** Moves time forward by {@code ms} (must not be negative). */
    public void advance(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("ms < 0");
        }
        now += ms;
    }
}
