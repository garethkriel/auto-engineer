# 20261124T1456Z — ae.columnar: columnar analytics store (level 20 capstone)

## Task
Build `ae.columnar`, a small in-memory column store for `long` columns:

* `BitPacking`: fixed-width unsigned packing into `long[]` words (width 0..64, values may straddle words).
* `EncodedColumn`: immutable column segment with five encodings (`PLAIN`, `RLE`, `DICT`, `FOR`, `DELTA`),
  `encodeBest` (smallest serialised size, ties broken by enum order), random access `get`, `decode`,
  zone map (`min`/`max`), predicate `scan(Op, value)` returning a `BitSet`, and `toBytes`/`fromBytes`
  with a CRC32 trailer and strict validation.
* `Table`: columns split into segments of `segmentRows` rows; `select(preds)` with zone-map pruning
  (stats: pruned / all-match / scanned segments), `aggregate` and `groupBy` (COUNT/SUM/MIN/MAX).

## Design / assumptions
* All comparisons are signed `long`. Pack/delta arithmetic is modulo 2^64 so full-range inputs work.
* FOR: `base=min`, packed `v-min` (unsigned). DELTA: first value + zigzag(wrapping difference) packed; checkpoints every 64 values for `get`.
* DICT: sorted distinct values, ids bit-packed. RLE: (value, runEnd) arrays, `get` by binary search.
* `scan` is specialised per encoding (run-level for RLE, per-dictionary-entry for DICT) and must equal a naive loop.
* Serialised form: `n(int) enc(byte) payload crc32(int)`. `fromBytes` rejects: bad CRC, truncation, trailing bytes,
  unknown encoding, impossible sizes (checked before allocating), non-canonical packing (non-zero padding bits),
  RLE runs of length 0 or not summing to n, DICT not strictly ascending / id out of range, width > 64.
* SUM overflow throws `ArithmeticException`; MIN/MAX of no rows is `OptionalLong.empty()`.
* Overlap check: listed `benchmark/java/` (j01..j35). j29-kvlog is a row-oriented crash-safe log, j35-minisql is a SQL engine; no columnar/encoding task.

## Success criteria
`mvn -q -B clean test` passes: literal-value tests per encoding, randomised differential tests against `long[]`
oracles (get/decode/scan for all ops and encodings, tables vs a naive row scan), exhaustive truncation and byte-flip
corruption tests, hand-built corrupt images re-sealed with a valid CRC, and pruning-stat tests.
