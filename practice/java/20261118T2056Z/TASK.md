# Task 20261118T2056Z — MVCC transactional key-value store (level 20 capstone)

Package `ae.mvcc`. Overlap check against `benchmark/java/` (kvlog, minisql, cache, ratelimit, queue...): kvlog is an append log; this task is multi-version concurrency control with snapshot isolation, which no benchmark covers. No shared core algorithm.

## Design
- `MvccStore<K extends Comparable<? super K>, V>`: sorted map key -> list of versions `(commitTs, value | tombstone)`, ascending by commitTs. One monotonic commit clock. All structural state guarded by one lock; transactions are cheap handles.
- `begin(Isolation)` takes a snapshot timestamp = last committed ts. Reads see the newest version with `commitTs <= startTs`, plus the transaction's own buffered writes (read-your-writes).
- `Isolation.SNAPSHOT`: commit fails with `ConflictException` if any written key has a version committed after startTs (first-committer-wins). Write skew is allowed.
- `Isolation.SERIALIZABLE`: additionally validates the read set and scanned ranges (phantoms): fail if any read key, or any key inside a scanned range, has a version committed after startTs.
- `scan(from, to)`: half-open range `[from, to)` in key order, merging snapshot and own writes, skipping tombstones.
- `vacuum()`: watermark = oldest active start ts (or last commit ts if none). Per key keep the newest version `<= watermark` and everything newer; drop older ones; drop the key entirely if the kept version is a tombstone and it is the oldest.
- Failed commit leaves the store unchanged and closes the transaction. Closed transactions throw `IllegalStateException`.
- Null keys/values rejected (`delete` is the way to remove).

## Success criteria
`mvn -q -B clean test` passes: unit tests, write-skew/phantom tests, vacuum tests, a seeded differential test against a snapshot-copy oracle, a multi-threaded transfer test conserving a total.
