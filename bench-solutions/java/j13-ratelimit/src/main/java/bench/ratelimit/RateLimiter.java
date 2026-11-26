package bench.ratelimit;

import java.util.function.LongSupplier;

/**
 * Token bucket with exact fractional refill. Balance is kept in nano-tokens
 * (1 token = 1e9 units); since refillPerSecond tokens/s equals refillPerSecond
 * nano-tokens per nanosecond, no rounding ever occurs.
 */
public final class RateLimiter {
    private static final long UNIT = 1_000_000_000L;

    private final long capacity;
    private final long rate;
    private final long capUnits;
    private final LongSupplier clock;
    private long balance;
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
        this.capacity = capacity;
        this.rate = refillPerSecond;
        this.capUnits = capacity * UNIT;
        this.clock = nanoClock;
        this.balance = capUnits;
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
        if (rate == 0 || balance >= capUnits) {
            return;
        }
        long missing = capUnits - balance;
        // elapsed * rate >= missing  <=>  elapsed >= ceil(missing / rate)
        long needed = (missing + rate - 1) / rate;
        if (elapsed >= needed) {
            balance = capUnits;
        } else {
            balance += elapsed * rate; // < missing, so no overflow
        }
    }

    public synchronized boolean tryAcquire(long permits) {
        if (permits < 1 || permits > capacity) {
            throw new IllegalArgumentException("permits out of range");
        }
        refill();
        long cost = permits * UNIT;
        if (balance >= cost) {
            balance -= cost;
            return true;
        }
        return false;
    }

    public synchronized long available() {
        refill();
        return balance / UNIT;
    }
}
