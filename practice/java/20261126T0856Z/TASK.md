# Task: ae.units — exact dimensional-analysis unit library

Level: 20 (`python tools/curriculum.py java`; the validator accepts 1-20).
Topic: units of measure with dimension algebra, exact rational conversion and affine temperatures.

Overlap check: `benchmark/java/` names are j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals, j06-lcs, j07-expr, j08-graph, j09-bank, j10-counter, j11-streams, j12-fileindex, j13-ratelimit, j14-json, j15-topo, j16-ranges, j17-cache, j18-diff, j19-allocate, j20-cron, j21-sheet, j22-regex, j23-segtree, j24-queue, j25-bizhours, j26-rwlock, j27-primes, j28-script, j29-kvlog, j30-miniserver, j31-ranked, j32-fsm, j33-pricing, j34-vclock, j35-minisql. None is a units/dimension library (j07-expr is arithmetic expression evaluation; this parses unit expressions only and its core is dimension algebra + exact conversion). No overlap.

## Classes (package `ae.units`)
- `Rational`: exact fraction (BigInteger, den > 0, normalised). `of(long)`, `of(long,long)`, `parseDecimal(String)` (accepts `-12.5`, `3e2`, `1.5E-3`), `plus/minus/times/dividedBy/negate/pow(int)`, `signum`, `compareTo`, `equals/hashCode`, `doubleValue`, `toString` (`n` or `n/d`). Division by zero and `0^negative` throw `ArithmeticException`.
- `Dimension`: exponent vector over L M T I Th N J (length, mass, time, current, temperature, amount, luminous). `of(String)`-free API: constants `NONE, LENGTH, MASS, TIME, CURRENT, TEMPERATURE, AMOUNT, LUMINOUS`, `times`, `dividedBy`, `pow(int)`, `exponent(int index)`, `isDimensionless()`, `describe()` (nonzero exponents in order, e.g. `M L T^-2`, exponent 1 printed bare, dimensionless is `1`), `equals/hashCode`. Overflow throws `ArithmeticException`.
- `Unit`: record `(Dimension dimension, Rational factor, Rational offset)`; SI value = `v*factor + offset`. `isAffine()` = offset != 0. `times/dividedBy/pow` throw `IllegalArgumentException` on an affine unit unless `pow(1)`.
- `UnitRegistry`: `parse(String)` returns a `Unit`. Grammar: `expr := term (('*'|'/') term)*` (left associative); `term := atom ('^' signedInt)?`; `atom := NAME | '1' | '(' expr ')'`. Whitespace is ignored between tokens. Exponent magnitude is at most 64. Symbol lookup is case sensitive: exact symbol first, otherwise one SI prefix + a prefixable symbol (longest prefix first).
  - Symbols: m g s A K mol cd, N Hz Pa J W C V ohm L t min h d in ft yd mi lb oz gal lbf bar atm psi cal eV degC degF (also `°C`, `°F`). Prefixes: Y Z E P T G M k h da d c m u µ n p f. Not prefixable: min h d in ft yd mi lb oz gal lbf atm psi degC degF.
  - Base mass is kg: `g` has factor 1/1000. 1 in = 0.0254 m, ft = 0.3048 m, yd = 0.9144 m, mi = 1609.344 m, lb = 0.45359237 kg, oz = lb/16, gal = 3.785411784 L (US), lbf = lb * 9.80665 m/s², bar = 100000 Pa, atm = 101325 Pa, cal = 4.184 J, eV = 1.602176634e-19 J, L = 0.001 m^3, t = 1000 kg.
  - degC: factor 1, offset 273.15 K. degF: factor 5/9, offset 459.67*5/9 K.
  - Errors: `IllegalArgumentException` for empty input, unknown symbol, syntax errors, trailing text, exponent out of range and any affine unit inside a compound expression (anything but the bare unit, `(degC)` or `degC^1`). The message names the offending text.
- `Quantity`: `(Rational value, Unit unit)`. `parse("12.5 km/h")` (number, then unit expression, unit optional = dimensionless), `to(Unit)` / `to(String)` exact conversion (IllegalArgumentException on dimension mismatch, message contains both dimensions), `plus/minus` (same dimension, result in the left unit; affine units rejected), `times/dividedBy(Quantity)`, `pow(int)`, `compareTo`-free `equalsQuantity(Quantity)` which compares SI values exactly, `siValue()`.

## Success criteria
`mvn -q -B clean test` passes: literal published conversions, prefix resolution table, grammar rejection table with controls, exact round trips on randomized unit pairs, dimension algebra properties, and affine temperature checks.
