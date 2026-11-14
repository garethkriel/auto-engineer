# file-kv-store

- **Description:** append-only journal-backed string key-value store: escaped `S`/`D` lines, torn-write recovery on open, line-numbered corruption errors, atomic compaction (temp file + move).
- **Language:** java
- **Tags:** nio, files, journal, TempDir, parameterized-tests, escaping, atomic-move
- **Source round:** practice/java/20261114T0756Z
- **Tests:** `mvn -q -B test` (26 tests)
