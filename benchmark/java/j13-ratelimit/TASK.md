# j13-ratelimit: Token-bucket rate limiter

Java 21, standard library only. Put your solution under `bench-solutions/java/j13-ratelimit/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.ratelimit;
public final class RateLimiter {
    public RateLimiter(long capacity, long refillPerSecond, java.util.function.LongSupplier nanoClock);
    public boolean tryAcquire(long permits);
    public long available();
}
```

- **The bucket:** it starts full, holding `capacity` tokens. It refills continuously at `refillPerSecond` tokens per second, with time read from `nanoClock` in nanoseconds. It never holds more than `capacity`.
- **Fractions are kept exactly:** refill progress must not be rounded per call. For example, at 3 tokens per second, three calls 1/3 second apart (in nanoseconds, summing to exactly one second) together add exactly 3 tokens.
- **`available()`** returns the whole number of tokens available now, rounded down.
- **`tryAcquire(permits)`** is all-or-nothing:
  - If at least `permits` tokens are available, it takes them and returns `true`.
  - Otherwise it takes nothing and returns `false`.
- **Clock behaviour:**
  - Time counts from the largest clock value seen so far, so a clock that goes backwards adds nothing.
  - The clock may jump very far ahead (up to near `Long.MAX_VALUE`). This must not overflow; the bucket is simply full.
- **Thread safety:** it must be safe under concurrent use. With 8 threads competing, exactly the available tokens are handed out.
- **Errors:** each of these throws `IllegalArgumentException`:
  - a `capacity` outside 1–1,000,000,000;
  - a `refillPerSecond` outside 0–1,000,000,000 (0 means it never refills);
  - a null clock;
  - `permits` outside 1–`capacity`.
