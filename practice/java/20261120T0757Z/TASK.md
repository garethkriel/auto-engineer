# Task 20261120T0757Z — exact integer computational-geometry engine (level 20 capstone)

Package `ae.geom`. Standard library only.

## Overlap check
`benchmark/java/` names (j01..j35: roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, ...,
segtree, ranges, minisql) contain no computational geometry. The `spatial-quadtree` skill is a point index, not
hull/sweep. No overlap.

## Assumptions
- Coordinates are `long` with |x|,|y| <= 1_000_000_000 (`Pt` constructor throws IllegalArgumentException otherwise).
  Then every cross product and squared distance fits in `long` (diffs <= 2e9, products <= 4e18, difference <= 8e18).
- All predicates are exact (no doubles).

## Design / API
- `record Pt(long x, long y)` with `Pt.ORDER` (lexicographic x then y); `Geom.orient(a,b,c)` -> sign of
  cross(b-a, c-a): +1 left turn, 0 collinear, -1 right turn.
- `record Seg(Pt a, Pt b)`; zero-length segments (points) allowed.
- `Geom.segmentRelation(s,t)` -> `Rel {NONE, POINT, OVERLAP}` (exact; OVERLAP = collinear sharing more than one point).
  `Geom.intersects(s,t)` = relation != NONE.
- `Geom.convexHull(List<Pt>)` -> CCW vertices, no collinear points, starting at the lexicographically smallest point,
  duplicates ignored, input untouched. 0/1/2 distinct points give 0/1/2 vertices (all collinear -> the 2 extremes).
- `Geom.area2(List<Pt>)` -> twice the signed area (positive for CCW) with Math.*Exact (ArithmeticException on overflow).
- `Geom.locate(List<Pt> polygon, Pt p)` -> `Loc {INSIDE, BOUNDARY, OUTSIDE}` for any simple polygon (either
  orientation); fewer than 3 vertices -> IAE.
- `Geom.isConvex(List<Pt>)` strictly convex simple polygon (no collinear triple, no reflex turn, no double winding),
  either orientation; fewer than 3 vertices -> false.
- `Geom.diameter2(List<Pt>)` -> max squared distance between two points (rotating calipers on the hull; 0 for <2 pts).
- `Geom.closestPair2(List<Pt>)` -> min squared distance among list entries (duplicates give 0); IAE for <2 points.
  Divide and conquer, O(n log n).
- `Sweep.findIntersection(List<Seg>)` -> `Optional<int[]>` {i,j}, i<j, of ANY intersecting pair (Shamos-Hoey, exact
  rational comparison via BigInteger); empty when none. Touching endpoints and overlaps count.

## Success criteria
`mvn -q -B test` green; differential tests against O(n^2)/O(n^3) oracles on seeded random inputs (tiny coordinate
ranges to force degeneracies) plus literal known answers.
