# Task (level 20 capstone): `ae.bigint` — arbitrary-precision integers from scratch

Overlap check: `benchmark/java/` (j01-j35) has no big-number task; `skills/java/` has `prime-sieve-number-theory` (long-based only) and `fraction` (long-based), no multi-limb arithmetic. Not a copy.

## Goal
`ae.bigint.Big`: immutable signed integer, sign + magnitude in little-endian base-2^32 `int[]` limbs (normalised: no high zero limbs, zero = empty array). `java.math.BigInteger` is used ONLY in tests as the oracle; main code must not import `java.math`.

## API (all public unless noted)
- `Big.ZERO`, `ONE`, `valueOf(long)`, `parse(String)` (radix 10, optional leading `-`/`+`), `parse(String, int radix)` (2..36, digits case-insensitive). Empty string, lone sign, bad digit -> `NumberFormatException`.
- `add`, `subtract`, `multiply`, `negate`, `abs`, `signum()`, `compareTo`, `equals`/`hashCode` (structural), `min/max` not needed.
- `multiply` uses Karatsuba above a limb threshold (32); package-private `static int[] mulSchool(int[],int[])` and `static int[] mulKaratsuba(int[],int[],int threshold)` are exposed for tests.
- `divideAndRemainder(Big)` -> `Big[]{q, r}` truncating toward zero (remainder has sign of dividend, like Java `/` and `%`); `divide`, `remainder`; `mod(Big m)` non-negative result, `m` must be > 0. Division by zero / non-positive modulus -> `ArithmeticException`. Knuth algorithm D; package-private counter `static long addBacks()` counts "qhat too big by one" corrections (to prove tests reach that path).
- `pow(int)` (negative exponent -> `ArithmeticException`; `0^0 = 1`), `modPow(Big e, Big m)` (e >= 0, m > 0; result in [0,m)), `gcd` (non-negative, `gcd(0,0)=0`), `modInverse(Big m)` (m > 0; `ArithmeticException` if none; result in [0,m)), `isqrt()` (floor sqrt, negative -> `ArithmeticException`).
- `shiftLeft(int)`, `shiftRight(int)` (floor semantics for negatives: `-5 >> 1 = -3`; negative shift amount shifts the other way), `bitLength()` = bit length of the magnitude (0 for zero).
- `toString()` radix 10, `toString(int radix)` (2..36, lowercase), `longValueExact()` (`ArithmeticException` if out of range, including `Long.MIN_VALUE` boundary).

## Success criteria
- Literal tests (100!, 2^200, limb boundaries, sign table of division) pass.
- Randomised differential tests vs `BigInteger` for every operation, including operands made of extreme limbs (0, 1, 0x7fffffff, 0x80000000, 0xffffffff) that hit the add-back path (asserted via counter > 0).
- Karatsuba (threshold 2) equals schoolbook on random and extreme operands of unequal lengths.
- `mvn -q -B clean test` passes.
