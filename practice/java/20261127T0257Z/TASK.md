# Task 20261127T0257Z - ae.sf: IEEE-754 binary64 in software

Overlap check: `ls benchmark/java` = j01-roman .. j35-minisql (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). None is floating-point arithmetic or number formatting. Existing skills cover fraction/bigint/poly but not IEEE-754 rounding, decimal<->binary conversion or shortest printing.

Level 20 capstone: build binary64 arithmetic from scratch on raw `long` bit patterns.

## API (package `ae.sf`)
`SoftDouble` (static, operates on raw bits `long`; NaN results are canonical `0x7FF8000000000000L`):
- constants `POS_INF`, `NEG_INF`, `NAN`; predicates `isNaN`, `isInfinite`, `isZero`; `neg`, `abs`.
- `add`, `sub`, `mul`, `div`, `sqrt`, `fma(a,b,c)` - correctly rounded, round-to-nearest-even, subnormals, overflow to infinity, signed zeros as IEEE.
- `lessThan`, `equal` (IEEE: NaN unordered, -0 == +0).
- `fromLong(long)` (rounded), `toLong(bits)` (truncating, saturating, NaN -> 0, like a Java cast).
`Decimal`:
- `parse(String)` -> bits. Strict grammar `[+-]?(digits[.digits*]|.digits)([eE][+-]?digits)?`, plus `NaN`/`Infinity` with optional sign; anything else (whitespace, hex, `d`/`f` suffix, empty) throws `NumberFormatException`. Correctly rounded for any digit count and exponent.
- `shortest(bits, minDigits)` -> `Digits(String digits, int exp10)` for finite nonzero values: value ~ d1.d2d3.. x 10^exp10, the shortest digit string (at least `minDigits`) that parses back to the same bits, closest to the true value, ties to even digit.
- `toString(bits)`: same text as JDK 21 `Double.toString` (min 2 digits rule, sci notation outside [1e-3,1e7)).

## Success criteria
`mvn -q -B clean test` passes: published literals, special-case tables, seeded randomized differential tests against JDK (`+ - * / Math.sqrt Math.fma Double.parseDouble Double.toString`), BigDecimal brute-force oracle for shortest digits, round-trip, and strictness rejections. A mutation script then checks tests kill mutants.

## Design
Every finite operation unpacks to exact `(neg, BigInteger mag, int exp)`, computes the exact result (division/sqrt with a sticky bit appended to a >=55-bit quotient), and rounds once in `round`. Correct by construction; the tests pin it down. Assumptions: JDK 21 `Double.toString` is the oracle for formatting.
