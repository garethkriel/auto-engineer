# ts-block-file

- **Description:** Compressed, checksummed time-series file (`ae.tsblock`): `TsWriter` (strictly increasing timestamps, delta-of-delta zigzag varint blocks, CRC32 per block, footer index with its own CRC, temp file + atomic move on close, `abort()`), `TsReader` (validates header/trailer/index on open, binary-searches the index so `range(from, toExclusive)` decodes only overlapping blocks, CRC-checks each block on decode).
- **Language:** Java 21, standard library only
- **Tags:** storage, binary-format, varint, zigzag, crc32, delta-encoding, footer-index, atomic-write, corruption-tests
- **Source round:** practice/java/20261117T2156Z
- **Reusable ideas:** wrapping 64-bit delta arithmetic round-trips every long; exhaustive truncation and every-byte-flip tests prove the corruption checks; counter on blocks decoded proves index pruning.
- **Run tests:** `mvn -q -B test`
