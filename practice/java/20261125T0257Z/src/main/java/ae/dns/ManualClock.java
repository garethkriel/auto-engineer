package ae.dns;

/** A clock that only moves when told to. */
public final class ManualClock implements Clock {
    private long now;

    @Override
    public long nowSeconds() {
        return now;
    }

    public void advance(long seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("seconds " + seconds);
        }
        now += seconds;
    }
}
