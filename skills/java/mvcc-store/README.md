# mvcc-store

- **Description:** In-memory multi-version key-value store (`ae.mvcc`): snapshot and serializable transactions, read-your-writes, half-open range scans, first-committer-wins, read-set and phantom validation, and watermark-based vacuum. Thread safe via one store lock.
- **Language:** java
- **Tags:** mvcc, transactions, snapshot-isolation, serializable, write-skew, vacuum, oracle-test
- **Source round:** practice/java/20261118T2056Z
- **Tests:** unit tests, write-skew/phantom tests, vacuum tests, seeded differential test vs a snapshot-copy model, multi-threaded transfer test. `mvn -q -B test`.
