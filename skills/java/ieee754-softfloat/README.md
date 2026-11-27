# ieee754-softfloat

- **Description:** IEEE-754 binary64 arithmetic on raw `long` bits with no floating-point ops: correctly rounded add/sub/mul/div/sqrt/fma (nearest-even, subnormals, overflow, signed zeros, NaN), `fromLong`/`toLong`, IEEE comparisons; plus `Decimal`: strict exact parser for any digit count/exponent and shortest round-trip printing (closest digits, ties to even) matching JDK 21 `Double.toString`.
- **Language:** java (JDK 21, standard library only)
- **Tags:** floating-point, ieee754, rounding, bigdecimal-free, shortest-roundtrip, parsing, fma, differential-testing
- **Source round:** practice/java/20261127T0257Z
- **Pattern worth reusing:** unpack to exact `mag * 2^exp`, compute exactly (sticky bit on a >= 55-bit quotient/root), round once in a single routine; test by differential comparison with the JDK plus a BigDecimal brute-force oracle for digit counts.
- **Caveats:** BigInteger-based, so correct but slow (not for hot paths). Not related to any benchmark task.

Tests: `mvn -q -B test` (27 tests; ~15 s).
