# Practice 20261119T1056Z — Level 20 capstone: incremental build engine (Shake-style)

## Task
Build `ae.build`, a small incremental build engine with **dynamic dependencies**, **early cutoff**
and a **crash-safe persistent store**. Standard library only.

## Overlap check
`benchmark/java/` has j15-topo (static topological sort) and j12-fileindex, j17-cache; none is a
build system with dynamically discovered dependencies, value-hash verification, early cutoff or a
checksummed store. Existing skills `job-scheduler` (static DAG + journal) and `content-addressed-vcs`
(object store) differ: here deps are discovered while a task runs and verified by value hash.

## Design
- Keys are strings. A key is either an **input** (`Builder.input(key, value)`) or a **rule**
  (`Builder.rule(key, Task)`); a key may not be both (IllegalArgumentException).
- `Task.run(Context)` returns a non-null String. `Context.need(key)` builds the key (recursively) and
  returns its value; every need is recorded, in first-need order and de-duplicated, as a dependency.
  Which deps a task has can depend on earlier values (dynamic deps).
- `Builder.build(key)` starts a *run*. Per run each key is resolved once (memoised, failures too).
  Resolve(rule): if the store has a record, resolve its recorded deps **in order** and compare each
  dep's current value hash to the recorded one; stop at the first difference. All equal → reuse the
  stored value without running (a dep that fails during verification just forces a rerun).
  Otherwise run the task and store `Record(value, hash, deps-with-hashes)` — only on success.
  **Early cutoff**: a rerun that produces an identical value leaves its dependents' verification clean.
- Failures: a task exception becomes `BuildException(key, cause)`; the old record stays untouched;
  dependents fail with the same exception object; unrelated keys still build. Cycles raise
  `CycleException` carrying the path `[a, b, c, a]`. Unknown key / missing input → BuildException.
- `Context` is only valid while its task runs (`IllegalStateException` afterwards).
- `lastExecuted()` lists the rules run in the last run in completion order; `lastReused()` the rules
  verified clean.
- `Store.save(Path)` / `Store.load(Path)`: canonical bytes (sorted keys), magic + version + CRC32,
  temp file + atomic move; corrupt files raise `CorruptStoreException` (an IOException).
  `Builder.prune(roots)` drops records unreachable from the roots via recorded deps.

## Success criteria
`mvn -q -B clean test` passes: literal-expectation tests (diamond, early cutoff, dynamic deps,
failure, cycle, unknown), persistence tests (round trip, canonical bytes, no temp files, exhaustive
truncation and byte-flip corruption), and a seeded randomized differential test (incremental result ==
from-scratch result; nothing runs when no input changed; executed ⊆ affected; each key at most once).
