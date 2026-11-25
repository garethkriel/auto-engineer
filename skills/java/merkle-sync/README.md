# merkle-sync

- **Description:** String->String map as a depth-4, 16-ary hash trie keyed by SHA-256 of the key (content-only root hash, cached hashes, pruning, invariant checker); inclusion and non-inclusion proofs with strict verification; top-down anti-entropy `Sync.diff`/`Sync.pull` that verifies every remote reply against the hash its parent advertised and commits only after the result reproduces the advertised root.
- **Language:** java (JDK only, JUnit 5)
- **Tags:** merkle-tree, hash-trie, proofs, anti-entropy, replica-sync, hostile-remote, invariant-checker, mutation-tested
- **Source round:** practice/java/20261125T0556Z
- **Tests:** 42 tests: independent from-scratch oracle after every random step, per-byte sibling flip loop, malformed-shape and tampered-bucket proofs, request-count bounds (1 for equal maps, at most 1+5k for k differing keys), honest and misfiled hostile remotes. A 30-mutant check left 2 equivalent survivors (sibling length check is subsumed by the hash comparison; the post-apply root check is subsumed by per-level verification plus the path check).
