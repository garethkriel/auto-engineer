# 20261127T0556Z — ae.dec arbitrary-precision decimal arithmetic

Level 20 capstone (topic: numeric library). Class `ae.dec.Dec`: an immutable decimal `unscaled * 10^-scale`
(`BigInteger` unscaled, `int` scale), written from scratch on top of `BigInteger` only (no `BigDecimal` in main code).

## Overlap check
`benchmark/java/` names read: j01-roman .. j35-minisql (j09-bank, j33-pricing are money apps, not a decimal
library; none is a numeric type). `skills/java` has `bigint-from-scratch` (integers, no scale/rounding) and
`ieee754-softfloat` (binary64); `grep -ril "roundingmode\|MathContext"` finds only users of BigDecimal. Not a duplicate.

## API (semantics follow `java.math.BigDecimal`, which the tests use as the oracle)
- `Dec.parse(String)`: `[+-]? (digits[.digits*] | .digits) ([eE][+-]?digits)?`; scale = fractionDigits - exponent.
  Anything else, or an exponent/scale that overflows `int`: `NumberFormatException`.
- `Dec.of(long)`, `Dec.of(BigInteger unscaled, int scale)`, `unscaled()`, `scale()`, `signum()`, `precision()` (digits; 0 has 1).
- `add`, `subtract` (scale = max), `multiply` (scale = sum, `Math.addExact`), `negate`, `abs`, `pow(int n)` for n >= 0.
- `compareTo` (numeric), `equals`/`hashCode` (same unscaled AND scale, so 2.0 != 2.00).
- `setScale(int, RoundingMode)`; `UNNECESSARY` with an inexact result throws `ArithmeticException`.
- `round(int precision, RoundingMode)`: precision 0 = unchanged; a carry (999 -> 1000) must still give exactly `precision` digits.
- `divide(Dec, int precision, RoundingMode)`: quotient correctly rounded to `precision` (>0) significant digits.
  Preferred scale = this.scale - divisor.scale. If the division is exact, trailing zeros are stripped
  down to the preferred scale (not below). Zero divisor: `ArithmeticException`. Zero dividend: zero at preferred scale.
- `divideExact(Dec)`: exact quotient at the smallest scale >= preferred scale, `ArithmeticException` if non-terminating.
- `sqrt(int precision, RoundingMode)`: negative: `ArithmeticException`; correctly rounded to `precision` digits;
  exact roots are stripped toward preferred scale this.scale/2.
- `stripTrailingZeros()` (zero -> `0`, scale 0), `toString()` (scientific when scale < 0 or adjusted exponent < -6, e.g. `1.23E+5`, `1E-7`), `toPlainString()`.

## Success criteria
`mvn -q -B clean test` green: literal examples, randomized differential tests against `BigDecimal` (all 8 rounding modes,
scale and value), rounding-carry and boundary (exact half) fixtures, alias/immutability checks, parse rejection with
a control input, and a small mutation pass recorded in the run log.

## Assumptions
Rounding mode `UNNECESSARY` for `divide`/`sqrt` throws when the result is inexact. `Integer` overflow of scale is
reported with `ArithmeticException` (parse: `NumberFormatException`).
