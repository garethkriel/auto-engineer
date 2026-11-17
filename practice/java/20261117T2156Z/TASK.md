# Task: tsblock — compressed, checksummed time-series file (level 14, storage)

Overlap check: `benchmark/java/` contains j29-kvlog (key/value log) among others; this task is a
columnar time-series block file with delta-of-delta varint compression and a footer index, not a
key-value log, so the core algorithm differs. Existing skills file-kv-store/rotating-log are
key-value and log rotation respectively; none does varint compression or block indexes.

## Requirements (package `ae.tsblock`)
- `record Point(long ts, long value)`.
- `TsWriter.create(Path target, int blockSize)`; `append(long ts, long value)` (timestamps strictly
  increasing, else `IllegalArgumentException`); `close()` finishes the file. Data is written to a
  temp file in the same directory and atomically moved onto `target` only on successful `close()`;
  `abort()` deletes the temp file. Nothing appears at `target` before `close()`.
- Block payload: varint count, then timestamps as zigzag-varint delta-of-delta and values as
  zigzag-varint deltas (wrapping 64-bit arithmetic, so extreme values round trip).
- File layout: magic `TSB1`, blocks `[u32 len][u32 crc32][payload]`, footer index (per block:
  offset, minTs, maxTs, count) with its own CRC, trailer `[u64 indexOffset][magic TSBE]`.
- `TsReader.open(Path)` validates trailer + index CRC (throws `CorruptFileException`, an
  `IOException`). `count()`, `minTs()`, `maxTs()` (empty file → `OptionalLong`/0 count),
  `range(from, toExclusive)` decodes only overlapping blocks (verifying their CRC), `all()`,
  `blocksDecoded()` counter.
- Empty file (no points) is valid.

## Success criteria
`mvn -q -B clean test` passes: round trips (incl. Long.MIN/MAX), range queries vs brute force,
block-skipping, corruption (bit flip in block, index, truncation, wrong magic), atomic-write,
compression ratio for regular data.
