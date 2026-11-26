# units-of-measure

- **Description:** Exact dimensional-analysis library `ae.units`: `Rational` (BigInteger fraction with bounded decimal parsing and power budget), `Dimension` (7-exponent vector with overflow-checked algebra), `Unit` (factor + offset, affine units isolated), `UnitRegistry.parse` (recursive-descent parser for `kg*m/s^2`, `(mm)^2`, SI prefixes resolved after exact symbols, factor-size budget), and `Quantity` (parse, exact `to`, plus/minus, times/dividedBy/pow, `equalsQuantity`).
- **Language:** java
- **Tags:** units, dimensional-analysis, rational, parser, affine-temperature, resource-budget
- **Source round:** practice/java/20261126T0856Z
- **Tests:** `mvn -q -B test` (207 tests: literals, prefix table, rejection table with controls, random differential vs independent table, random expression evaluator, mutation-driven budget tests)
