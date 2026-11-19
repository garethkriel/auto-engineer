# spatial-quadtree

- **Description:** 2-D point quadtree keyed by string id (`ae.spatial.SpatialIndex`): auto-growing square bounds, insert/remove/move with collapse on removal, closed-rectangle range, radius, best-first k-nearest with deterministic (distance, id) ties, polygon containment including boundary, DBSCAN clustering on top of radius queries, and canonical CRC32 snapshots whose `restore` rejects truncation, bit flips and re-sealed structural corruption. Includes a package-private `checkInvariants()`.
- **Language:** java
- **Tags:** quadtree, spatial-index, k-nearest, dbscan, polygon, snapshot, crc32, differential-testing
- **Source round:** `practice/java/20261119T0356Z`
- **Tests:** `mvn -q -B test` (38 tests: literal boundaries, brute-force differential runs with invariant checks, snapshot corruption loops, DBSCAN oracle).
- **Reuse notes:** put every list result in an explicit total order; expand tree nodes before tied entries in a best-first queue; test polygon code against an exact cross-product oracle, not a shifted ray.
