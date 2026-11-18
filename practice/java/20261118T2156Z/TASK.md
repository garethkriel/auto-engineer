# Task: ae.vcs — a tiny content-addressed version store (level 20 capstone)

Overlap check: `benchmark/java/` holds j01-roman … j35-minisql (diff, kvlog, minisql, cron, script, …).
This task is a content-addressed commit DAG with merge-base and path-level three-way merge: it shares
no core algorithm with any of them (j18-diff is line diffing; this never diffs file contents).

## Design (written first)

Layers (ports and adapters, standard library only):

- `ObjectId` — record of 64 lowercase hex chars = SHA-256 of the *raw* object bytes.
- `Codec` — raw object = `kind SP length NUL payload`; kinds `blob`, `tree`, `commit`. Canonical encodings:
  tree = lines `<hex> <path>\n` sorted by UTF-8 byte order; commit = `tree`, `parent`*, `author`, `time` header lines, blank line, message.
- `ObjectStore` port: `put(raw) -> id`, `get(id) -> Optional<raw>` (verifies the hash, throws `CorruptObjectException`).
  Adapters: `MemoryObjectStore`, `FileObjectStore` (fan-out dirs, temp file + atomic move, no temp left behind).
- `Refs` port (branch name -> commit id): `MemoryRefs`, `FileRefs` (atomic writes).
- `Repo` — commit a full snapshot (`path -> bytes`) to a branch, branch, checkout, log (children before parents,
  ties by newer time then smaller id), isAncestor, mergeBase (best common ancestor; with several, newest then smallest id),
  diff of two commits, merge, fsck.
- Merge: fast-forward / up-to-date detection; otherwise path-level three-way rule against the merge base
  (empty base for unrelated histories): equal sides -> take; one side equals base -> take the other; else conflict.
  Conflict kinds: BOTH_CHANGED, CHANGED_DELETED, FILE_DIRECTORY (merged result would have `a` as file and `a/b`).
  On conflict nothing is written and no ref moves.

Assumptions: paths are `/`-separated, non-empty segments, no `.`/`..`, no NUL/newline; a path may not be
both a file and a directory prefix; branch names match `[A-Za-z0-9_][A-Za-z0-9._-]*`; author has no newline;
empty commits (tree equal to parent's) are rejected; the clock is injected (`LongSupplier`).

## Success criteria

- Known SHA-256 literals for blob/tree ids; tree id independent of insertion order; commit encode/decode round trip incl. awkward messages.
- File store: round trip, idempotent put, flipped-byte and truncation detection, no temp files left, refs survive reopen.
- Repo: log order, mergeBase incl. criss-cross, diff, merge truth table, fast-forward, conflicts leave state untouched, fsck finds missing/corrupt objects.
- Randomised differential tests: mergeBase vs brute force on random DAGs; merge vs an independent per-path oracle; merge symmetry.
- `mvn -q -B clean test` passes.
