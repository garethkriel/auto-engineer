package bench.ratelimit;

import java.util.function.LongSupplier;

/** Token bucket; tokens are tracked exactly in units of 1/1e9 token. */
public final class RateLimiter {
    private static final long UNIT = 1_000_000_000L;
    private final long capacity;
    private final long scaledCapacity;
    private final long refill;
    private final LongSupplier clock;
    private long scaledTokens;
    private long last;

    public RateLimiter(long capacity, long refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || capacity > 1_000_000_000L) throw new IllegalArgumentException("capacity");
        if (refillPerSecond < 0 || refillPerSecond > 1_000_000_000L) throw new IllegalArgumentException("refill");
        if (nanoClock == null) throw new IllegalArgumentException("clock");
        this.capacity = capacity;
        this.scaledCapacity = capacity * UNIT;
        this.refill = refillPerSecond;
        this.clock = nanoClock;
        this.scaledTokens = scaledCapacity;
        this.last = nanoClock.getAsLong();
    }

    private void advance() {
        long now = clock.getAsLong();
        if (now <= last) return;
        long elapsed;
        boolean overflow = false;
        try {
            elapsed = Math.subtractExact(now, last);
        } catch (ArithmeticException e) {
            elapsed = Long.MAX_VALUE;
            overflow = true;
        }
        last = now;
        if (refill == 0) return;
        long missing = scaledCapacity - scaledTokens;
        if (overflow || elapsed > missing / refill + 1) {
            scaledTokens = scaledCapacity;
        } else {
            scaledTokens = Math.min(scaledCapacity, scaledTokens + elapsed * refill);
        }
    }

    public synchronized boolean tryAcquire(long permits) {
        if (permits < 1 || permits > capacity) throw new IllegalArgumentException("permits");
        advance();
        long cost = permits * UNIT;
        if (scaledTokens >= cost) {
            scaledTokens -= cost;
            return true;
        }
        return false;
    }

    public synchronized long available() {
        advance();
        return scaledTokens / UNIT;
    }
}
