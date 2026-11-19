# rsync-delta

- **Language:** Java 21 (standard library only)
- **Tags:** rsync, rolling-checksum, delta-sync, binary-format, crc32, sha-256, atomic-write, directory-sync
- **Source round:** practice/java/20261119T2256Z

Package `ae.rsync`: rsync-style delta synchronisation.

- `Rolling`: Adler-style weak checksum with O(1) `roll(out, in)`; bytes are unsigned, sums wrap mod 2^16.
- `Signature`: per-block (weak, SHA-256) of a basis; checksummed wire format; the last block may be short.
- `DeltaEncoder.compute(sig, target)`: slides a window over the target; a weak hit is accepted only after the strong hash
  matches (a crafted weak collision is tested). Copies contiguous in the basis are merged. Short tail block matches only
  when exactly that many bytes remain.
- `Delta` / `Patcher`: copy+literal ops, wire format with CRC32, length and SHA-256 of the target; `Patcher` verifies copy
  ranges (overflow-safe) and the digest, so a wrong basis is detected rather than silently producing bad data.
- `DirSync.sync(src, dst, blockSize)`: tree sync that routes each file through serialised signature and delta messages,
  writes via temp file + atomic move, prunes emptied dirs, and reports wire/literal byte counts.

Reusable ideas: structure validation tests with re-sealed CRC (`WireTestSupport.reseal`), truncate-at-every-length and
flip-every-byte loops, collision construction via second difference (+1,-2,+1) which preserves both sums.
Run: `mvn -q -B test` (57 tests).
