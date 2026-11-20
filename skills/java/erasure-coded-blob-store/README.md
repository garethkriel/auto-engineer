# erasure-coded-blob-store

- **Language:** Java 21 (JDK only)
- **Tags:** erasure-coding, reed-solomon, galois-field, gf256, cauchy-matrix, mds, blob-store, crc32, scrub, repair
- **Source round:** practice/java/20261120T0356Z

`ae.erasure`: `Gf256` (log/exp tables, poly 0x11D), `GfMatrix` (Gauss-Jordan inverse), `ReedSolomon` (systematic Cauchy
code: encode, reconstruct from any k shards, verify) and `BlobStore` (k+m shard files in node dirs, CRC-sealed headers,
per-put id to reject stale shards, `status`/`get`/`repair`/`delete`, temp-file + atomic move writes).

Reuse notes: test the MDS property exhaustively over every erasure mask for small codes; cross-check field
multiplication against a carry-less oracle; a per-write id in headers is the only way to reject same-length stale replicas;
test header consistency checks with re-sealed (valid CRC) forged headers. Tests: `mvn -q -B test` (47 tests).
