# Task: Directory snapshot and diff (level 6, file I/O + @TempDir + parameterised tests)

Implement `ae.Snapshot` and `ae.Diff` in Java 21 (stdlib only):

- `Snapshot.of(Path dir, String... ignoreGlobs)`: walk `dir` recursively, SHA-256 every regular file (symlinks not followed, skipped). Keys are relative paths with `/` separators. Files whose relative path matches any ignore glob (`FileSystem.getPathMatcher("glob:...")`) are skipped. Null/missing/non-directory -> `IllegalArgumentException`. A file name containing a tab or newline -> `IllegalArgumentException`.
- `Snapshot.write(Path file)` / `Snapshot.read(Path file)`: text format `<hex-hash>\t<path>` per line, sorted; written atomically (temp file + move). `read` throws `IOException` on malformed lines (bad hash, no tab, duplicate path).
- `Snapshot.diff(Snapshot after)` -> `Diff(List<String> added, removed, modified)`, each sorted, immutable. `Diff.isEmpty()`, `Diff.render()` (lines `+ path`, `- path`, `~ path`, sorted by path within each group order added, removed, modified; empty diff renders `no changes`).
- Equal snapshots have equal `equals`/`hashCode`.

Success: `mvn -q -B test` passes.

Assumptions: Overlap check — benchmark/java has roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex. Fileindex maps words to files; this task hashes file content and diffs trees, so the core algorithm differs.
