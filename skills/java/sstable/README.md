# sstable

- **Description:** Immutable sorted-string table (`ae.sst`): `SstWriter` (strictly ascending keys by unsigned UTF-8 byte order, tombstones, sparse index every 4th entry, Bloom filter block, footer with CRC32, temp file + atomic move, no file on invalid input), `SstReader` (validates magic, offsets, CRC, entry walk and index consistency on open; `get` via Bloom filter + index binary search; `scan` ranges), `SstMerge` (k-way compaction, newest table wins, optional tombstone dropping), `BloomFilter`.
- **Language:** Java 21, standard library only
- **Tags:** storage, sstable, bloom-filter, sparse-index, crc32, tombstone, compaction, atomic-write, utf8-ordering
- **Source round:** practice/java/20261117T2356Z
- **Reusable ideas:** build the whole file image in memory before touching disk so validation failures leave nothing behind; compare keys as unsigned UTF-8 bytes consistently (String.compareTo differs for surrogates); craft images with a re-sealed valid CRC to test structural validation separately; randomized merge vs a TreeMap model.
- **Run tests:** `mvn -q -B test`
