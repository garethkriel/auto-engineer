# Task: append-only file-backed key-value store (level 6)

Implement `kv.FileKvStore`, a string->string store persisted in a journal file via `java.nio.file`.

- `static FileKvStore open(Path file) throws IOException`: creates the file if missing; replays the journal.
- `put(key, value)`, `remove(key)` (returns whether the key existed), `get(key)` -> `Optional<String>`, `size()`, `keys()` (sorted, unmodifiable).
- Every mutation appends one line: `S<TAB>key<TAB>value` or `D<TAB>key`. Keys/values may contain tabs, newlines, CR and backslashes, so they are escaped (`\\`, `\t`, `\n`, `\r`).
- Torn write: if the file does not end with `\n`, the last partial line is dropped and truncated on open.
- A malformed complete line makes `open` throw `IOException` naming the 1-based line number.
- `compact()` rewrites the journal with one `S` line per live key (sorted) via temp file + atomic move.
- Null key/value -> `NullPointerException`; empty key -> `IllegalArgumentException`. Failed validation must not change state.

Success: `mvn -q -B test` passes. Tests use `@TempDir` and `@ParameterizedTest`.
Assumption: single process, single thread. Benchmark overlap check: j01-roman..j12-fileindex (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex); a journaled KV store is none of these (not an index of files, not a cache).
