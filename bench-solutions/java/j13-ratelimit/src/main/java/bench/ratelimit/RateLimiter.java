package bench.ratelimit;

import java.util.function.LongSupplier;

/** Thread-safe token bucket with exact fractional refill (tokens are tracked scaled by 1e9). */
public final class RateLimiter {
    private static final long SCALE = 1_000_000_000L;
    private final long capacityScaled;
    private final long rate;
    private final LongSupplier clock;
    private long scaled;
    private long last;

    public RateLimiter(long capacity, long refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || capacity > 1_000_000_000L) throw new IllegalArgumentException("capacity");
        if (refillPerSecond < 0 || refillPerSecond > 1_000_000_000L) throw new IllegalArgumentException("refill");
        if (nanoClock == null) throw new IllegalArgumentException("clock");
        this.capacity = capacity;
        this.capacityScaled = capacity * SCALE;
        this.rate = refillPerSecond;
        this.clock = nanoClock;
        this.scaled = capacityScaled;
        this.last = nanoClock.getAsLong();
    }

    private final long capacity;

    private void refill() {
        long now = clock.getAsLong();
        if (now <= last) return;
        long elapsed;
        try {
            elapsed = Math.subtractExact(now, last);
        } catch (ArithmeticException e) {
            elapsed = Long.MAX_VALUE;
        }
        last = now;
        if (rate == 0 || scaled >= capacityScaled) return;
        long add;
        try {
            add = Math.multiplyExact(elapsed, rate);
        } catch (ArithmeticException e) {
            scaled = capacityScaled;
            return;
        }
        scaled = add >= capacityScaled - scaled ? capacityScaled : scaled + add;
    }

    public synchronized boolean tryAcquire(long permits) {
        if (permits < 1 || permits > capacity) throw new IllegalArgumentException("permits");
        refill();
        long need = permits * SCALE;
        if (scaled >= need) {
            scaled -= need;
            return true;
        }
        return false;
    }

    public synchronized long available() {
        refill();
        return scaled / SCALE;
    }
}
