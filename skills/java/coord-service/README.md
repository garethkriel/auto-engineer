# coord-service

- **Language:** Java 21 (standard library only)
- **Tags:** coordination, zookeeper, sessions, ephemeral-nodes, watches, multi-transaction, distributed-lock, fencing-token, staged-commit
- **Source round:** practice/java/20261119T1157Z

ZooKeeper-style in-process kernel `ae.coord.CoordService`: hierarchical nodes with versions/zxids, persistent/ephemeral/sequential creation, lazily expiring sessions on an injected `Clock`, one-shot data/child watches dispatched after commit and outside the lock, atomic `multi` (Create/Delete/SetData/Check) using a staged copy of an immutable-node map. `DistributedLock` is a fair lock recipe (predecessor watch, sequence number = fencing token).

Reuse ideas: staged-copy transaction commit; run callbacks after releasing the monitor; invariant checker + naive-model differential test.
Tests: `mvn -q -B test` (43 tests incl. seeded randomized differential test).
