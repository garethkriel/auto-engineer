# decimal-arith

- **Language:** Java 21 (standard library only; `BigInteger` internally)
- **Tags:** decimal, BigDecimal, rounding modes, scale, division, sqrt, number formatting
- **Source round:** `practice/java/20261127T0556Z`

`ae.dec.Dec`: immutable decimal `unscaled * 10^-scale` re-implementing the `java.math.BigDecimal` semantics from scratch:
strict `parse`, `add/subtract/multiply/pow`, `setScale` and `round` in all 8 `RoundingMode`s (with carry handling),
`divide(d, precision, mode)` with the preferred-scale/strip-zeros rule, `divideExact` (terminates iff denominator is 2^a5^b),
correctly rounded `sqrt` (exact half-way ties handled with `4*num` vs `(2r+1)^2*den`), `toString` (scientific when
scale < 0 or adjusted exponent < -6), `toPlainString`, `stripTrailingZeros`, `equals` (scale-sensitive) vs `compareTo`.

Tests: 79 (literal tables, parse rejection with controls, randomised differential tests against `BigDecimal` with fixed seeds).
A 17-row mutation pass (all killed except the equivalent hashCode mutant) is recorded in the source round log.
Run: `mvn -q -B test`.
