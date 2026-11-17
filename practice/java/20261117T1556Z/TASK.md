# Task: bit-packed prime sieve and 64-bit number theory (level 12, performance)

Package `perf`.

- `PrimeSieve.of(int limit)`: sieve of Eratosthenes storing ONLY odd numbers, one bit each, in a `long[]` (about limit/16 bytes).
  - `isPrime(int n)`: false for n < 2; `IllegalArgumentException` if n > limit.
  - `count()`: primes <= limit. `countUpTo(int n)`: primes <= n (n may be < 2; n > limit throws).
  - `nth(int k)`: k-th prime, 1-based; `IllegalArgumentException` if k < 1 or k > count().
  - `primes()`: ascending `int[]`.
  - `of` with negative limit throws `IllegalArgumentException`; limit 0/1 gives an empty sieve.
- `NumberTheory`:
  - `gcd(long,long)` (non-negative result; gcd(0,0)=0; must work for Long.MIN_VALUE via exact handling -> throws ArithmeticException if result not representable, i.e. gcd(MIN,0) and gcd(MIN,MIN)).
  - `modPow(long base, long exp, long mod)`: mod >= 1, exp >= 0, base any (negative allowed, result in [0,mod)); correct for mod up to Long.MAX_VALUE.
  - `isPrime(long n)`: deterministic Miller-Rabin, correct for all longs (n < 2 false).
  - `factorize(long n)`: n >= 2, returns `SortedMap<Long,Integer>` prime -> exponent; product must reproduce n. Must be fast for n = large prime near 2^62 (trial division only up to a bound, then Miller-Rabin; for semiprimes with both factors above the bound use Pollard rho).
Success: all tests pass with `mvn -q -B test`; time-bounded tests (sieve of 50 million < 2 s, factoring large 62-bit semiprimes < 2 s).

Overlap check vs `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours): none is number theory/sieve. No overlap.

Assumptions: Pollard rho with Floyd/Brent and fixed deterministic constants; Java 21 stdlib only.
