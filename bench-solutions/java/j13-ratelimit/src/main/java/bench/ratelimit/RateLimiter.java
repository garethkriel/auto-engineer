package bench.ratelimit;

import java.util.function.LongSupplier;

public final class RateLimiter {
    private static final long UNIT = 1_000_000_000L; // fixed-point: 1 token = 1e9 units

    private final long capacity;
    private final long refill;
    private final long capUnits;
    private final LongSupplier clock;
    private long units;
    private long last;

    public RateLimiter(long capacity, long refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || capacity > 1_000_000_000L) throw new IllegalArgumentException("capacity");
        if (refillPerSecond < 0 || refillPerSecond > 1_000_000_000L) throw new IllegalArgumentException("refill");
        if (nanoClock == null) throw new IllegalArgumentException("clock");
        this.capacity = capacity;
        this.refill = refillPerSecond;
        this.capUnits = capacity * UNIT;
        this.clock = nanoClock;
        this.units = capUnits;
        this.last = nanoClock.getAsLong();
    }

    private void advance() {
        long now = clock.getAsLong();
        if (now <= last) return;
        long elapsed = now - last;
        if (elapsed < 0) elapsed = Long.MAX_VALUE; // subtraction overflowed
        last = now;
        if (refill == 0 || units >= capUnits) return;
        long need = capUnits - units;
        if (elapsed > need / refill + 1) {
            units = capUnits;
        } else {
            units = Math.min(capUnits, units + elapsed * refill);
        }
    }

    public synchronized boolean tryAcquire(long permits) {
        if (permits < 1 || permits > capacity) throw new IllegalArgumentException("permits");
        advance();
        long need = permits * UNIT;
        if (units >= need) {
            units -= need;
            return true;
        }
        return false;
    }

    public synchronized long available() {
        advance();
        return units / UNIT;
    }
}
