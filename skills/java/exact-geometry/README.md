# exact-geometry

- **Description:** exact integer computational geometry in package `ae.geom`: orientation, segment relation
  (NONE/POINT/OVERLAP), Andrew monotone-chain hull, shoelace area with overflow detection, point-in-polygon with
  boundary detection, strict convexity (incl. winding check), rotating-calipers diameter, divide-and-conquer closest
  pair and a Shamos-Hoey any-intersection sweep with BigInteger rational comparison.
- **Language:** java
- **Tags:** geometry, convex-hull, sweep-line, closest-pair, exact-arithmetic, differential-testing
- **Source round:** practice/java/20261120T0757Z
- **Key ideas:** coordinates capped at 1e9 so cross products fit in `long`; half-open straddle rule for the ray
  test; sweep events sorted insert-before-remove; comparator ties broken by id; oracles in the tests are independent
  all-triples / O(n^2) implementations.
- **Tests:** `mvn -q -B test` (32 tests).
