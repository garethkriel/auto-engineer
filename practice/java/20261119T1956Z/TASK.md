# Capstone: disk-backed B+tree with buffer pool (package `ae.btree`)

## Design
Layers, bottom up:
1. `PageFile` - fixed-size pages in a `RandomAccessFile`; counts physical reads/writes.
2. `BufferPool` - LRU cache of page frames with pin/unpin, dirty tracking, write-back on eviction/flush. Throws `IllegalStateException` when every frame is pinned.
3. `BPlusTree` - `long -> long` map stored in pages through the pool.

Page layout (P = page size, min 64, big-endian):
- Page 0 meta: magic int, root int (0 = empty), pageCount int, freeHead int (0 = none), size long.
- Leaf: type=1 (byte), count (short), next int, prev int, then count*(key long, value long). Capacity (P-11)/16.
- Internal: type=2, count (short) = number of keys n, child0 int, then n*(key long, child int). Capacity (P-7)/12 keys. Child i holds keys k with key[i-1] <= k < key[i].
- Free page: type=3, next free int.
Insert splits upward (leaf split copies the separator up, internal split moves it up). Delete removes from the leaf; a leaf that becomes empty is unlinked from the chain and removed from its parent; an internal node left with no children is removed likewise; a root internal with one child collapses (height shrinks). No borrowing/merging of partly full nodes (documented trade-off). Freed pages go on a free list and are reused.
Durability: only on `flush()`/`close()` (no WAL); reopen after close sees all data. A wrong magic or inconsistent meta throws `IOException`.

## API
`BPlusTree.open(Path, int pageSize, int poolFrames)`; `Optional<Long> get(long)`; `Optional<Long> put(long,long)` (returns old); `Optional<Long> remove(long)`; `List<Entry> range(long lo, long hiInclusive)`; `long size()`; `int height()`; `void check()` (throws `IllegalStateException` on any invariant violation); `flush()`, `close()`. `record Entry(long key,long value)`.

## Success criteria
`mvn -q -B clean test` passes: PageFile/BufferPool unit tests, differential tests against `TreeMap` with `check()` after every mutation, persistence, free-list reuse, tiny pool, corruption detection.
Assumptions: single-threaded; pool needs at least height+3 frames.
