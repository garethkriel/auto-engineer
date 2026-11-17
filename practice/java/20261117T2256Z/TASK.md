# Task: slotted-page heap file (level 14, storage)

Overlap check: `benchmark/java/` has j29-kvlog (key/value log) and j12-fileindex; existing skills cover
log rotation, KV stores and a time-series block file. This task is a *page-oriented record heap*
(slotted pages, record ids, compaction, per-page CRC32) and shares no core algorithm with them.

## Package `ae.heap`
- `CorruptDataException extends IOException`.
- `SlottedPage(int pageSize)` (64..32768, else IAE), in-memory page over a `byte[]`:
  - layout: `u16 slotCount, u16 dataStart`, slot directory (`u16 offset, u16 length`, length 0xFFFF = deleted),
    records growing down from `pageSize-4`, last 4 bytes = CRC32 of everything before.
  - `int insert(byte[])` -> slot id (reusing a deleted slot first) or -1 if it cannot fit (compacts when holes make it fit).
  - `Optional<byte[]> get(int slot)` (empty if deleted; IAE if slot out of range).
  - `boolean delete(int slot)` (false if already deleted).
  - `boolean update(int slot, byte[])` (false if deleted or no room; failure leaves the page unchanged).
  - `int liveCount()`, `int freeSpace()` (total reusable bytes for a record needing a new slot, incl. holes).
  - `byte[] toBytes()` (CRC filled), `static SlottedPage fromBytes(byte[])` validates CRC and structure
    (bounds, overlap) else `CorruptDataException`.
- `HeapFile`: `HeapFile.open(Path, int pageSize)`; `Rid(int page,int slot)` record; `Rid insert(byte[])`
  (IAE if bigger than a page can hold), `Optional<byte[]> get(Rid)`, `boolean delete(Rid)`,
  `boolean update(Rid, byte[])` (stays in place or fails: false), `List<Entry> scan()` in (page,slot) order,
  `void flush()` writing atomically (temp file in same dir + ATOMIC_MOVE). File: 16-byte header
  (magic, pageSize, pageCount, CRC32) then pages. `open` of a missing file = empty heap; truncated/corrupt -> CorruptDataException.

## Success
`mvn -q -B clean test` passes; tests cover round trips, reuse of slots, compaction, boundary sizes,
corruption detection (flipped byte, truncation, crafted overlapping slots with valid CRC), no leftover temp files,
and a randomized model check against a `HashMap`.
