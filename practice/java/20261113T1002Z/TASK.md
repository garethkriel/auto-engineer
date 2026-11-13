# Task 20261113T1002Z — Fraction value class (level 2: classes, constructors, equals/hashCode, toString)

Implement `ae.Fraction`, an immutable exact fraction of two `long`s.

- `Fraction.of(long num, long den)`: static factory; always stored reduced, denominator > 0. Zero denominator -> `IllegalArgumentException`. `0/x` is `0/1`. Overflow on negating `Long.MIN_VALUE` -> `ArithmeticException`.
- `Fraction.parse(String)`: accepts `"a/b"` or `"a"` (whitespace trimmed); invalid input -> `IllegalArgumentException`; null -> `NullPointerException`.
- `plus`, `minus`, `times`, `dividedBy` (divide by zero fraction -> `ArithmeticException`); use `Math.*Exact` so overflow throws `ArithmeticException`.
- `numerator()`, `denominator()`, `compareTo` (Comparable; correct without overflow), `equals`/`hashCode` consistent (reduced form), `toString` = `"n/d"`, or `"n"` when denominator is 1.
- `static final ZERO`, `ONE`.

Success: `mvn -q -B test` passes; tests cover equality of unreduced inputs, negatives, parse errors, overflow, compareTo near Long.MAX_VALUE.

Assumption: compareTo uses BigInteger cross-multiplication to avoid overflow.
