package bench.ratelimit;

import java.util.function.LongSupplier;

/**
 * Token bucket with exact fractional refill. Tokens are stored in units of 1e-9 token,
 * so elapsedNanos * refillPerSecond is an exact integer amount of such units.
 */
public final class RateLimiter {
    private static final long UNIT = 1_000_000_000L;

    private final long maxUnits;
    private final long rate;
    private final LongSupplier clock;
    private long units;
    private long last;

    public RateLimiter(long capacity, long refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || capacity > 1_000_000_000L) {
            throw new IllegalArgumentException("capacity out of range");
        }
        if (refillPerSecond < 0 || refillPerSecond > 1_000_000_000L) {
            throw new IllegalArgumentException("refillPerSecond out of range");
        }
        if (nanoClock == null) {
            throw new IllegalArgumentException("clock is null");
        }
        this.maxUnits = capacity * UNIT;
        this.rate = refillPerSecond;
        this.clock = nanoClock;
        this.units = maxUnits;
        this.last = nanoClock.getAsLong();
    }

    private void refill() {
        long now = clock.getAsLong();
        if (now <= last) {
            return;
        }
        long elapsed = now - last;
        if (elapsed < 0) { // overflowed: gap exceeds Long.MAX_VALUE
            elapsed = Long.MAX_VALUE;
        }
        last = now;
        if (rate == 0 || units >= maxUnits) {
            return;
        }
        long need = maxUnits - units;
        long fullAfter = need / rate + (need % rate == 0 ? 0 : 1);
        if (elapsed >= fullAfter) {
            units = maxUnits;
        } else {
            units += elapsed * rate; // < need, cannot overflow
        }
    }

    public synchronized boolean tryAcquire(long permits) {
        long capacity = maxUnits / UNIT;
        if (permits < 1 || permits > capacity) {
            throw new IllegalArgumentException("permits out of range");
        }
        refill();
        long cost = permits * UNIT;
        if (units >= cost) {
            units -= cost;
            return true;
        }
        return false;
    }

    public synchronized long available() {
        refill();
        return units / UNIT;
    }
}
