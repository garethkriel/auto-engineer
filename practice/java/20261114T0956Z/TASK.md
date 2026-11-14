# Task 20261114T0956Z: size-based rotating log file (level 6, java.nio.file)

Implement `rl.RotatingLog(Path file, long maxBytes, int maxBackups)`:
- `append(String line)` writes `line + "\n"` (UTF-8) to `file`. Lines containing `\n` or `\r` are rejected (IllegalArgumentException).
- If the current file is non-empty and adding the line would exceed `maxBytes`, rotate first: `file.N` is deleted, `file.(k)` moves to `file.(k+1)`, `file` moves to `file.1`; with `maxBackups == 0` the file is simply truncated. A line larger than `maxBytes` is written alone into a fresh file (never split).
- Existing file size is honoured at construction. `currentSize()`, `backups()` (existing backup paths, newest first).
- Constructor rejects null, `maxBytes < 1`, `maxBackups < 0`.
- Rotation must not leave temp files; failures throw `UncheckedIOException`.

Success: `mvn -q -B test` passes. Overlap check vs benchmark/java (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex): none shares a core algorithm (this is file rotation).
Assumption: sizes are counted in UTF-8 bytes.
