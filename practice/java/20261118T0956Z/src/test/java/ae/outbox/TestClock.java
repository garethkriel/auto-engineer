package ae.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Settable clock for deterministic tests. */
final class TestClock extends Clock {
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private volatile Instant now = START;

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
