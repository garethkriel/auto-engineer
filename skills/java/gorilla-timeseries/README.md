# gorilla-timeseries

- **Language:** Java 21 (JDK only)
- **Tags:** time-series, compression, bit-streams, delta-of-delta, xor-float, block-skipping, retention, snapshot, crc32
- **Source round:** practice/java/20261120T0256Z

`ae.tsdb`: `BitWriter`/`BitReader` (MSB-first), `GorillaCodec` (delta-of-delta timestamps + XOR doubles, bit-exact
round trip incl. NaN payloads and -0.0), and `TimeSeriesDb` (sealed compressed blocks + uncompressed head,
`[from,to)` queries that skip blocks by min/max, aligned NaN-ignoring `aggregate`, whole-block `retain`, canonical
CRC32 `snapshot`/`restore` with structural validation).

Reuse notes: validate everything before mutating (append), keep decoders strict (count sanity check, padding must be
zero, trailing bytes rejected), and test with truncation, flip-every-bit and re-sealed-CRC corrupted images.
Tests: `mvn -q -B test` (35 tests).
