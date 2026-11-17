# prime-sieve-number-theory

- **Language:** Java 21 (stdlib only)
- **Tags:** performance, bit manipulation, sieve, primes, Miller-Rabin, Pollard rho, modPow, gcd
- **Source round:** practice/java/20261117T1556Z

`perf.PrimeSieve`: odd-only bit-packed sieve (limit/16 bytes) with isPrime, count, countUpTo (prefix popcounts), nth (clear-lowest-bit scan), primes.
`perf.NumberTheory`: gcd safe for Long.MIN_VALUE, overflow-free modPow/mulMod (Math.multiplyHigh), deterministic Miller-Rabin for all longs, factorize (trial division to 4096 then Pollard rho).
Tests include published prime counts, strong pseudoprimes, BigInteger cross-checks and 2 s time bounds (sieve of 50M; 62-bit semiprimes).
