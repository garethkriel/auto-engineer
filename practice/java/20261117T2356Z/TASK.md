# Level 14 (storage): immutable sorted-string table (SSTable) with sparse index, Bloom filter, CRC and k-way merge

Overlap check: `benchmark/java/` has j29-kvlog (append-only log) and j12-fileindex; this is an immutable sorted
table file with bloom filter and merge compaction, no shared core algorithm. Existing skills file-kv-store,
ts-block-file and slotted-heap-file are different formats.

## Requirements (package `ae.sst`)
- `Entry(String key, byte[] value)`: `value == null` is a tombstone. Keys are non-empty, at most 65535 UTF-8 bytes.
  Keys are ordered by **unsigned UTF-8 byte order** (not `String.compareTo`).
- `SstWriter.write(Path file, Iterable<Entry> sorted)`: keys must be strictly ascending, else `IllegalArgumentException`
  and **no file is created**. Writes via temp file + atomic move in the same directory; no temp file remains on failure.
  Returns the entry count. Empty input is allowed.
- File layout: magic `SST1`, data entries `[u16 keyLen][i32 valLen (-1 tombstone)][key][value]`, sparse index (every 4th
  entry: key + u64 offset), Bloom filter block, footer `[u64 indexOff][u64 bloomOff][u32 count][u32 crc32][magic SST1]`;
  the CRC covers every byte before the crc field.
- `SstReader.open(Path)`: verifies magic, size, offsets and CRC; throws `CorruptSstException` (an `IOException`) on any
  damage (flip any single byte => detected). `get(String)` returns `Lookup` (`FOUND`/`DELETED`/`ABSENT` + value),
  uses Bloom filter then sparse-index binary search. `scan(from, toExclusive)` returns ordered entries (null bound = open).
  `count()`, `entries()`.
- `BloomFilter`: no false negatives, serialisable; false-positive rate below 3% at 10 bits/key.
- `SstMerge.merge(Path out, List<SstReader> newestFirst, boolean dropTombstones)`: k-way merge, newer table wins on equal key,
  tombstones kept or dropped.

## Success criteria
`mvn -q -B clean test` passes; tests cover: UTF-8 ordering (U+FFFF vs supplementary), exact lookups for all keys, absent keys
between and outside range, tombstones, scan bounds, every-byte corruption detection, crafted valid-CRC bad offsets,
unsorted/duplicate input leaves no file, merge precedence vs a TreeMap model on random data, Bloom FP rate.
