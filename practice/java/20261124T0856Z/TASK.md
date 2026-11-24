# Round 20261124T0856Z - Level 20 capstone: adaptive range coder and context-model compressor

## Task
Package `ae.rangecoder`: a from-scratch entropy-coding stack, standard library only.

1. `RangeEncoder` / `RangeDecoder`: LZMA-style carry-propagating 32-bit range coder (`low` 33-bit, `cache`/`cacheSize` byte delay, 5-byte flush). Two coding styles share one stream:
   - frequency coding: `encode(cum, freq, total)` / `target(total)` + `consume(cum, freq, total)`, `1 <= total <= 65536`;
   - adaptive binary coding with `BitModel` (11-bit probability, shift 5): `encodeBit(model, bit)` / `decodeBit(model)`.
   Decoder over-read (more bytes than the stream holds) or an impossible target throws `CodecException`.
2. `FreqModel` (interface) and `FenwickModel`: adaptive symbol model, `n` symbols, every symbol starts at frequency 1, `update(sym)` adds 32, halves all frequencies (keeping each >= 1) when total would exceed 65536 - 32... (limit `LIMIT = 1 << 16`). Fenwick tree gives O(log n) `cumFreq` and `findSymbol(target)`.
3. `Compressor`: `byte[] compress(byte[] in, Engine e)`, `byte[] compressBest(byte[] in)` (smallest of all engines, ties -> lowest ordinal), `byte[] decompress(byte[])`.
   Engines: `FREQ0`, `FREQ1`, `FREQ2` (symbol models in order-0/1/2 byte contexts, order-2 contexts created lazily in a `HashMap`), `BIT0`, `BIT1` (bit-tree of 255 `BitModel`s per context, context = none / previous byte).
   Container: `'R','C'`, engine ordinal byte, big-endian int length, big-endian int CRC-32 of the original, then coder bytes. `decompress` throws `CodecException` for bad magic, unknown engine, truncated header, length above `MAX_RATIO * (coderBytes + 1)` (MAX_RATIO = 16384) or above 1<<28, over-read, and CRC mismatch.

## Success criteria
- Round trip for every engine on: empty, one byte, all 256 byte values, random bytes, text, 1,000,000 equal bytes.
- Coded size within ~3% of the empirical order-0 entropy (computed independently in the test) for an iid source; order-1 engines clearly beat order-0 on a Markov source.
- Every single-byte corruption of a container and every truncation either throws `CodecException` or (never, thanks to CRC) returns different data: the assertion is "throws or equals original".
- Carry propagation really happens in tests (`RangeEncoder.carries() > 0`) and is correct.
- A hand-mutation check (script) kills at least 6 mutants.

## Assumptions / design notes
- Overlap check: benchmark j01-j35 has no entropy coder (j14-json, j18-diff, j06-lcs unrelated); existing skill `deflate-codec` is LZ+Huffman, this is arithmetic coding.
- `total` must be a plain int <= 65536 so `range/total >= 2^8` after normalisation (range >= 2^24).
- Decoder `code` is stored relative to `low`, so the carry never touches the decoder.
