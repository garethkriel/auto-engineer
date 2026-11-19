# deflate-codec

- **Language:** Java 21, standard library only (JDK `java.util.zip` used in tests as an oracle).
- **Tags:** deflate, zlib, huffman, lz77, bit-stream, adler32, codec, differential-testing
- **Source round:** `practice/java/20261119T0556Z`

RFC 1951/1950 codec: `Inflate` (stored/fixed/dynamic blocks, output cap against bombs, only `InflateException` on bad input),
`Deflate` (hash-chain LZ77 + fixed Huffman or stored fallback), `Zlib` wrap/unwrap, own `Adler32`, `BitReader/BitWriter`, `Huffman`.
Reuse the `BitReader`/`BitWriter`/`Huffman` trio for other bit-packed formats. Tests cross-check against `Deflater`/`Inflater`
at levels 0-9 and exhaustively truncate / bit-flip streams.

Gotchas: fixed distance code has 32 slots (30/31 invalid) or the table is "incomplete"; JDK raw `Inflater` may need a dummy byte and
`finished()` must be checked before `needsInput()`; fixed-Huffman 258-byte matches cost ~13 bits so 1 MB of zeros is ~6 KB.
