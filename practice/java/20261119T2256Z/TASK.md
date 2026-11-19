# 20261119T2256Z: rsync-style delta synchronisation (level 20 capstone)

Language: Java 21, standard library only. Package `ae.rsync`.

## Overlap check
`benchmark/java/` contains j18-diff (line-based LCS edit script), j12-fileindex, j29-kvlog, j30-miniserver and others.
This task is byte-level delta sync with a rolling weak checksum, a strong hash, a binary wire format and directory
sync. It shares no core algorithm with any benchmark directory (the nearest, j18-diff, is LCS over lines).

## Design
Receiver holds an old `basis`; sender holds the `target`. Receiver sends a `Signature` of the basis; sender replies with
a `Delta` (copy-from-basis and literal ops); receiver patches. Both messages have a checksummed binary format.

- `Rolling`: Adler-style weak checksum over a window: a = sum(x_i) mod 2^16, b = sum((n-i)*x_i) mod 2^16,
  weak = a | b<<16. `roll(out, in)` is O(1). Bytes are unsigned.
- `Signature(blockSize, fileLength, List<BlockSig(weak, strong)>)`; strong = SHA-256 of the block. The last block
  may be shorter. `Signature.of(basis, blockSize)`, `toBytes()`, `parse(bytes)`.
  Format: "RSS1", u32 blockSize, u64 fileLength, u32 blockCount, per block u32 weak + 32 bytes SHA-256, u32 CRC32 of everything before.
  Parse rejects bad magic, bad CRC, blockCount != ceil(fileLength/blockSize), truncation, trailing bytes, all with `FormatException`.
- `Delta(List<Op>, long targetLength, byte[] targetSha256)`; `Op` sealed: `Copy(long offset, int length)` and `Literal(byte[] data)`.
  Adjacent copies contiguous in the basis are merged; adjacent literals are merged. No empty ops.
  Format: "RSD1", u32 opCount, ops (tag 0: u64 offset, u32 length; tag 1: u32 length + bytes), u64 targetLength, 32 bytes SHA-256, u32 CRC32.
- `DeltaEncoder.compute(Signature, byte[] target)`: slide a window over the target. A full-size window uses the rolling
  weak sum; a candidate block is accepted only if the strong hash matches (weak collisions must never cause a wrong copy).
  The short last basis block can only match when exactly its length remains at the end of the target.
  On a match, jump past the block and restart the window; otherwise emit one literal byte and roll by one.
- `Patcher.apply(basis, delta)`: validates every copy is in range, checks the result length and SHA-256; throws
  `PatchException` (basis does not match) instead of returning wrong data.
- `DirSync.sync(Path src, Path dst, int blockSize)`: makes dst's regular files equal to src's (recursive, "/" keys).
  Each changed file goes through serialised Signature -> Delta -> patch; new files use an empty basis; extra files in dst are deleted;
  files are written temp file + atomic move in the same directory. Returns `Report` (files created/updated/unchanged/deleted,
  signature bytes, delta bytes, literal bytes, total source bytes). Re-running on a synced tree transfers no literal bytes.

## Success criteria
- Round trip equality for random edits (insert, delete, replace, move) across block sizes, including 1.
- Exact op shapes for identical, appended, prefix-inserted, truncated targets; literal-byte bounds for single edits.
- A crafted weak-checksum collision is not copied.
- Corrupted signature/delta bytes (truncate at every length, flip every byte) never yield wrong data: exception or identical object.
- Wrong basis detected by Patcher.
- DirSync tests with @TempDir including idempotence and no leftover temp files.
- `mvn -q -B clean test` passes.
