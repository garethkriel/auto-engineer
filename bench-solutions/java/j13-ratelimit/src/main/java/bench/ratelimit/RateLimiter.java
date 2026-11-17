package bench.ratelimit;

import java.util.function.LongSupplier;

/**
 * Token bucket. Tokens are stored scaled by 1e9 so that refill (nanos x tokens/second)
 * is exact integer arithmetic with no rounding.
 */
public final class RateLimiter {
    private static final long SCALE = 1_000_000_000L;

    private final long capacity;
    private final long capacityScaled;
    private final long refillPerSecond;
    private final LongSupplier clock;
    private long scaled;
    private long lastNanos;

    public RateLimiter(long capacity, long refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || capacity > 1_000_000_000L || refillPerSecond < 0 || refillPerSecond > 1_000_000_000L
                || nanoClock == null) {
            throw new IllegalArgumentException("bad arguments");
        }
        this.capacity = capacity;
        this.capacityScaled = capacity * SCALE;
        this.refillPerSecond = refillPerSecond;
        this.clock = nanoClock;
        this.scaled = capacityScaled;
        this.lastNanos = nanoClock.getAsLong();
    }

    private void refill() {
        long now = clock.getAsLong();
        if (now <= lastNanos) {
            return;
        }
        long elapsed = now - lastNanos; // wraps negative if the true gap exceeds Long.MAX_VALUE
        lastNanos = now;
        if (refillPerSecond == 0) {
            return;
        }
        long missing = capacityScaled - scaled;
        if (elapsed < 0 || elapsed > missing / refillPerSecond) {
            scaled = capacityScaled;
        } else {
            scaled = Math.min(capacityScaled, scaled + elapsed * refillPerSecond);
        }
    }

    public synchronized boolean tryAcquire(long permits) {
        if (permits < 1 || permits > capacity) {
            throw new IllegalArgumentException("permits out of range");
        }
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
