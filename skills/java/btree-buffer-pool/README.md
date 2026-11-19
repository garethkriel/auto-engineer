# btree-buffer-pool

- **Description:** Disk-backed `long->long` B+tree (`ae.btree.BPlusTree`) over a fixed-size `PageFile` and a pinning LRU `BufferPool` with dirty write-back. Doubly linked leaves for range scans, free-list page reuse, empty-node unlinking on delete (no partial-node rebalancing), persistence on flush/close, and `check()` invariant verification.
- **Language:** java
- **Tags:** b+tree, buffer pool, pager, storage, invariants, differential testing
- **Source round:** practice/java/20261119T1956Z
- **Notes:** pool needs >= height+3 frames; no WAL, so durability is only at `flush()`/`close()`.
- **Test:** `mvn -q -B test` (20 tests).
