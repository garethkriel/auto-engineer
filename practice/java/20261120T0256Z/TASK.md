# Task: `ae.tsdb` — a compressed time-series store (level 20 capstone)

Overlap check (RULES): `benchmark/java/` names: roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter,
streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours,
rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql. None is a time-series store or a
bit-level float codec. `windowed-aggregation` (skill) works on event streams with watermarks; this round is storage and
compression (Gorilla-style delta-of-delta timestamps + XOR floats) with block-skipping queries. No benchmark task copied.

## Assumptions
Timestamps are non-negative longs, strictly increasing per series (out-of-order or duplicate appends are rejected).
Values are any `double`, including NaN, infinities and -0.0, and must round-trip bit-exactly (raw bits).
Aggregates ignore NaN points. Time ranges are half-open `[from, to)`.

## Design (written first)
Package `ae.tsdb`.
- `BitWriter` / `BitReader`: MSB-first bit streams; `writeBits(value, n)` for n in 0..64; reader throws
  `CorruptDataException` (unchecked) at end of data.
- `Point(long ts, double value)` record.
- `GorillaCodec.encode(List<Point>) -> byte[]`, `decode(byte[]) -> List<Point>`. Layout: 32-bit count; first point as
  64-bit ts + 64-bit raw value; then per point: delta-of-delta of ts with buckets
  `0` | `10`+7b (-63..64) | `110`+9b (-255..256) | `1110`+12b (-2047..2048) | `1111`+64b; value XOR with previous raw
  bits: `0` if equal, else `1` + (`0` reuse previous leading/trailing window | `1` + 5b leading (capped 31) + 6b
  (length-1)) + meaningful bits. Encoder rejects non-increasing timestamps; decoder rejects bad control codes, trailing
  garbage bits beyond the final byte padding (more than 7 bits) and truncated data.
- `TimeSeriesDb(int blockSize)`: per series a list of sealed compressed `Block(min, max, count, data)` plus an
  uncompressed head. `append`, `query(series, from, to)` (skips blocks by min/max), `aggregate(series, from, to, step,
  Agg)` -> non-empty `Bucket(start, count, value)` list with buckets aligned to `from`, `latest`, `retain(cutoff)`
  (drops whole sealed blocks with max < cutoff, returns dropped point count), `pointCount`, `compressedBytes`.
- `snapshot()` / `TimeSeriesDb.restore(bytes)`: canonical (series sorted by name) image with magic, version, block
  size, and a CRC32 trailer; restore verifies the CRC and then validates structure (block decodes to the declared
  count/min/max, blocks and head strictly ordered) and throws `CorruptDataException`.

## Success criteria
`mvn -q -B clean test` passes: bit stream round trips, codec table and randomized round trips incl. special doubles
and extreme timestamps, compression-ratio bounds, store queries against a naive list model, aggregates, retention,
snapshot round trip, truncation and flip-every-byte corruption loops, re-sealed (valid CRC) structural corruption.
