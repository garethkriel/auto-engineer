# columnar-store

- **Language:** Java 21 (standard library only)
- **Tags:** columnar, bit-packing, run-length, dictionary, frame-of-reference, delta, zone-map, serialisation, crc32, aggregation
- **Source round:** `practice/java/20261124T1456Z`

Immutable `long` column segments (`EncodedColumn`: PLAIN/RLE/DICT/FOR/DELTA over `BitPacking`), `encodeBest` by serialised size, per-encoding predicate `scan` to `BitSet`, zone-map pruning via `Op.prune`, a canonical CRC32-sealed byte format whose `fromBytes` rejects every non-canonical image, and a segmented `Table` (`select` with pruning stats, `aggregate`, `groupBy`).

Reusable ideas: zero-width packing, zigzag deltas with wrapping arithmetic and 64-value checkpoints for random access, verdict-based zone-map pruning (`NONE/MAYBE/ALL`), strict decoders with size checks before allocation and a row cap.

Tests: `mvn -q -B test` (31 tests: literal layouts, differential scans, truncation/flip loops, crafted re-sealed images with controls, table vs naive row scan).
