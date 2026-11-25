package bench.ratelimit;

import java.util.function.LongSupplier;

public final class RateLimiter {
    private static final long NANOS = 1_000_000_000L;
    private final long capacity;
    private final long refill;
    private final long maxUnits; // capacity * 1e9, in token-nanos
    private final LongSupplier clock;
    private long units;          // tokens * 1e9, exact
    private long last;

    public RateLimiter(long capacity, long refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || capacity > 1_000_000_000L) throw new IllegalArgumentException("capacity");
        if (refillPerSecond < 0 || refillPerSecond > 1_000_000_000L) throw new IllegalArgumentException("refill");
        if (nanoClock == null) throw new IllegalArgumentException("clock");
        this.capacity = capacity;
        this.refill = refillPerSecond;
        this.maxUnits = capacity * NANOS;
        this.clock = nanoClock;
        this.units = maxUnits;
        this.last = nanoClock.getAsLong();
    }

    private void update() {
        long now = clock.getAsLong();
        if (now <= last) return;
        long elapsed;
        try {
            elapsed = Math.subtractExact(now, last);
        } catch (ArithmeticException e) {
            elapsed = Long.MAX_VALUE;
        }
        last = now;
        if (refill == 0) return;
        long missing = maxUnits - units;
        // elapsed * refill >= missing  <=>  elapsed >= ceil(missing / refill)
        long needed = missing / refill + (missing % refill == 0 ? 0 : 1);
        if (elapsed >= needed) units = maxUnits;
        else units += elapsed * refill; // < missing <= 1e18, no overflow
    }

    public synchronized boolean tryAcquire(long permits) {
        if (permits < 1 || permits > capacity) throw new IllegalArgumentException("permits");
        update();
        long cost = permits * NANOS;
        if (units >= cost) {
            units -= cost;
            return true;
        }
        return false;
    }

    public synchronized long available() {
        update();
        return units / NANOS;
    }
}
