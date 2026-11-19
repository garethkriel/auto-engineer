# Capstone: `ae.spatial` 2-D spatial index service (level 20)

Overlap check: `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql) contain no spatial/geometry task. `j23-segtree` is 1-D array range queries, a different problem.

Java 21, standard library only. Package `ae.spatial`.

## Design

- `Point(double x, double y)`: record; NaN/infinite coordinates throw `IllegalArgumentException`.
- `Rect(double minX, double minY, double maxX, double maxY)`: record, closed on all sides, `min <= max` required (else `IllegalArgumentException`). `contains(Point)`, `intersects(Rect)`, `distanceSquared(Point)` (0 when inside).
- `Entry(String id, Point point)`: record.
- `SpatialIndex`: point quadtree + `HashMap<String,Point>` id table. Node capacity 8, max depth 32 (a node at max depth may hold any number of points, so coincident points work). Root bounds are a square; inserting outside it rebuilds the tree on a square twice as wide covering old bounds and the new point. Removal collapses a node into a leaf when its subtree holds `<= 8` points. A point exactly on a midline goes to the upper/right child.

API (ids are non-null, non-empty; a duplicate id on `insert` throws `IllegalArgumentException`):
- `insert(id, x, y)`, `remove(id)` -> boolean, `move(id, x, y)` (atomic: if the id is unknown or coordinates invalid it throws and the index is unchanged), `get(id)` -> `Optional<Point>`, `size()`.
- `range(Rect)` -> entries inside the closed rectangle, sorted by id.
- `radius(cx, cy, r)` -> entries with distance <= r (closed disk; compare squared distances), sorted by (distance, id). `r < 0` throws.
- `nearest(x, y, k)` -> the k closest entries, sorted by (squared distance, id); fewer if `size() < k`; `k < 0` throws, `k == 0` empty. Ties are broken by id even across tree cells.
- `inPolygon(List<Point>)` -> entries inside or on the boundary of a simple polygon (3+ vertices, else `IllegalArgumentException`), sorted by id. Even-odd ray casting; a point on an edge or vertex counts as inside (exact check: cross product 0 and within the segment's bounding box).
- `cluster(eps, minPts)` -> `Clusters(List<List<String>> clusters, List<String> noise)` using DBSCAN with neighbourhood = `radius(eps)` (includes the point itself). Process ids in ascending order; an unvisited id with `>= minPts` neighbours is a core point and seeds a new cluster that is expanded breadth-first (neighbours visited in (distance, id) order); border points join the first cluster that reaches them; everything else is noise. Clusters are listed in seed order, members sorted by id, noise sorted by id. `eps < 0` or `minPts < 1` throws.
- `snapshot()` -> canonical `byte[]`: magic `SPX1`, int count, then entries sorted by id (UTF-8 id with int length, double x, double y as raw long bits), then a trailing CRC32 (int) of everything before it. Equal contents give identical bytes regardless of insertion order or tree shape. `static SpatialIndex restore(byte[])` throws `CorruptSnapshotException` (checked) on any truncation, bad magic, bad CRC, negative/oversized counts, duplicate ids or invalid coordinates, and never any other exception type.
- `checkInvariants()` (package-private): every point lies in its leaf's bounds, node counts match, leaves hold `<= 8` points unless at max depth, no internal node holds `<= 8` points, and the tree holds exactly the id table's entries.

## Success criteria

All JUnit tests pass with `mvn -q -B test`: literal boundary cases, tree-shape structural tests, randomized differential tests against a brute-force list (invariant checker after every mutation), snapshot canonical/corruption loops, and DBSCAN literal and oracle tests.
- `depth()` (package-private) -> maximum node depth, 0 for a single leaf or empty index.
