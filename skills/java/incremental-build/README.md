# incremental-build

- **Language:** java
- **Tags:** build-system, incremental, dynamic-dependencies, early-cutoff, memoisation, persistence, crc32, differential-test
- **Source round:** practice/java/20261119T1056Z

Shake-style incremental build engine (`ae.build`): `Builder.rule(key, Task)` / `input(key, value)`;
tasks call `ctx.need(key)` so dependencies are discovered dynamically and recorded in first-need
order. A rule reruns only if a recorded dep's value hash changed (verified in order, stopping at the
first difference); an unchanged rerun result cuts off its dependents (early cutoff). Failures are
memoised per run and never overwrite the old record; cycles give `CycleException` with the path.
`Store.save/load` is canonical (sorted), CRC32-sealed, temp-file + atomic move; `Builder.prune`.

Reuse: the verify-deps-then-rerun core and the differential test (incremental vs from-scratch, nothing
runs when nothing changed) fit any memoised/derived-data cache.
