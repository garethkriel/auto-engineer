package bench.ratelimit;

import java.util.function.LongSupplier;

/** Thread-safe token bucket with exact fractional refill (tokens scaled by 1e9). */
public final class RateLimiter {
    private static final long NANOS = 1_000_000_000L;

    private final long capacityScaled;
    private final long refillPerSecond;
    private final LongSupplier clock;
    private long scaled;
    private long last;

    public RateLimiter(long capacity, long refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || capacity > 1_000_000_000L) {
            throw new IllegalArgumentException("capacity out of range: " + capacity);
        }
        if (refillPerSecond < 0 || refillPerSecond > 1_000_000_000L) {
            throw new IllegalArgumentException("refillPerSecond out of range: " + refillPerSecond);
        }
        if (nanoClock == null) {
            throw new IllegalArgumentException("clock is null");
        }
        this.capacityScaled = capacity * NANOS;
        this.refillPerSecond = refillPerSecond;
        this.clock = nanoClock;
        this.scaled = capacityScaled;
        this.last = nanoClock.getAsLong();
    }

    private void refill() {
        long now = clock.getAsLong();
        if (now <= last) {
            return;
        }
        long elapsed;
        try {
            elapsed = Math.subtractExact(now, last);
        } catch (ArithmeticException e) {
            elapsed = Long.MAX_VALUE;
        }
        last = now;
        if (refillPerSecond == 0 || scaled >= capacityScaled) {
            return;
        }
        long room = capacityScaled - scaled;
        if (elapsed > room / refillPerSecond + 1) {
            scaled = capacityScaled;
            return;
        }
        long add = elapsed * refillPerSecond; // elapsed <= room/refill+1, so no overflow
        scaled = add >= room ? capacityScaled : scaled + add;
    }

    public synchronized boolean tryAcquire(long permits) {
        if (permits < 1 || permits > capacityScaled / NANOS) {
            throw new IllegalArgumentException("permits out of range: " + permits);
        }
        refill();
        long need = permits * NANOS;
        if (scaled >= need) {
            scaled -= need;
            return true;
        }
        return false;
    }

    public synchronized long available() {
        refill();
        return scaled / NANOS;
    }
}
