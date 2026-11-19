# Task (level 20 capstone): `ae.deflate` — a DEFLATE / zlib codec from scratch

Overlap check against `benchmark/java/`: nothing there is about compression, bit streams or Huffman coding
(closest: `j18-diff` text diff, `j29-kvlog` log files; neither shares an algorithm). Recent rounds covered locks, spatial
index, windowing, VFS, VCS; none covered codecs.

## Design
RFC 1951 (DEFLATE) and RFC 1950 (zlib), standard library only, **no `java.util.zip` in `src/main`**. The JDK `Deflater`/`Inflater`
appear only in tests, as independent oracles.

- `BitWriter` / `BitReader`: LSB-first bit packing; Huffman codes are written MSB-first (bit-reversed).
- `Huffman`: canonical decoder built from code lengths (counts + sorted symbols, as in zlib's `puff`). Over-subscribed or
  incomplete sets are rejected, except the single-1-bit-code case.
- `Inflate.inflate(byte[] in, int offset, int maxOutput) -> Result(data, consumed)`: stored, fixed and dynamic blocks.
  Every malformed input raises `InflateException` (never an unchecked JDK exception); output is capped by `maxOutput`
  (zip-bomb guard).
- `Deflate.compress(byte[], Level)`: LZ77 with 32 KiB window and hash chains (chain depth by level), emitted as ONE
  fixed-Huffman block, or stored blocks when that is smaller (and always for `Level.STORED`). Empty input -> `03 00`.
- `Adler32`: own implementation. `Zlib.wrap/unwrap`: 2-byte header (CM=8, FCHECK valid, FDICT rejected), Adler-32 trailer
  verified, trailing garbage rejected.

## Success criteria
1. Known vectors: `"a"` -> `4b 04 00`, `"hello"` -> `cb 48 cd c9 c9 07 00`, empty -> `03 00`, zlib `"hello"` ends `06 2c 02 15`.
2. Our `Inflate` decodes JDK `Deflater` output at levels 0-9 and all strategies, on empty/1-byte/zeros/random/text inputs.
3. JDK `Inflater` decodes our `Deflate` output; our own round trip too (seeded random inputs, window-edge distances 32768/32769).
4. Corruption: every truncation prefix and every single-byte flip of a stream yields data or `InflateException`, nothing else.
5. `maxOutput` stops a decompression bomb; zlib checksum/header/trailing-data errors are rejected.
