package bench.ratelimit;

import java.util.function.LongSupplier;

public final class RateLimiter {
    private static final long SCALE = 1_000_000_000L;
    private final long capacity;
    private final long rate;
    private final long capUnits;
    private final LongSupplier clock;
    private long units;   // tokens * 1e9
    private long last;

    public RateLimiter(long capacity, long refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || capacity > 1_000_000_000L) throw new IllegalArgumentException("capacity");
        if (refillPerSecond < 0 || refillPerSecond > 1_000_000_000L) throw new IllegalArgumentException("refill");
        if (nanoClock == null) throw new IllegalArgumentException("clock");
        this.capacity = capacity;
        this.rate = refillPerSecond;
        this.capUnits = capacity * SCALE;
        this.clock = nanoClock;
        this.units = capUnits;
        this.last = nanoClock.getAsLong();
    }

    private void refill() {
        long now = clock.getAsLong();
        if (now <= last) return;
        long elapsed = now - last;
        boolean overflow = elapsed < 0; // subtraction overflowed
        last = now;
        if (rate == 0) return;
        if (overflow || elapsed > (capUnits - units) / rate + 1) {
            units = capUnits;
            return;
        }
        units = Math.min(capUnits, units + elapsed * rate);
    }

    public synchronized boolean tryAcquire(long permits) {
        if (permits < 1 || permits > capacity) throw new IllegalArgumentException("permits");
        refill();
        long need = permits * SCALE;
        if (units >= need) {
            units -= need;
            return true;
        }
        return false;
    }

    public synchronized long available() {
        refill();
        return units / SCALE;
    }
}
