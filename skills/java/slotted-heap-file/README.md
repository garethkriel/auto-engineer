# slotted-heap-file

- **Description:** `ae.heap.SlottedPage` (fixed-size page: slot directory, stable slot ids, delete with slot reuse, in-place or compacting update, CRC32 + structural validation on load) and `ae.heap.HeapFile` (records addressed by `Rid(page, slot)`, first-fit insert, scan, atomic temp-file flush, checksummed header).
- **Language:** java
- **Tags:** storage, binary-format, crc32, slotted-page, heap-file, atomic-write, compaction
- **Source round:** practice/java/20261117T2256Z
- **Notes:** failed `update` leaves the page byte-identical; `freeSpace()` is the largest record insertable into a new slot. Tests include per-byte flip detection and randomized model checks.
