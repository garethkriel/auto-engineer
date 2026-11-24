# bigint-from-scratch

- **Description:** `ae.bigint.Big`, an immutable arbitrary-precision integer built on base-2^32 `int[]` limbs without `java.math`: add/subtract, schoolbook + Karatsuba multiply, Knuth algorithm D division (truncating `divideAndRemainder`, non-negative `mod`), `pow`, `modPow`, `gcd`, `modInverse`, `isqrt`, floor-semantics shifts, radix 2..36 parse/format, `longValueExact`. Tests use `BigInteger` as oracle, extreme-limb operands (with an add-back counter proving that path runs), an exhaustive small-limb division grid and Karatsuba-vs-schoolbook checks.
- **Language:** java
- **Tags:** bignum, knuth-d, karatsuba, differential-testing, modular-arithmetic, radix-conversion
- **Source round:** practice/java/20261124T0356Z
- **Tests:** `mvn -q -B test` (57 tests)
- **Known equivalent mutants:** dropping the top-limb fix-up after add-back, and removing the `qhat > MASK` guard (add-back repairs the overshoot).
