# content-addressed-vcs

- **Description:** Tiny content-addressed version store (`ae.vcs`): SHA-256 object ids over `kind len NUL payload`, canonical tree/commit encodings (UTF-8 byte path order), memory and file object stores (atomic writes, hash verified on read), branch refs, commit/checkout/log/diff, merge-base (criss-cross aware), path-level three-way merge with fast-forward, conflicts that write nothing, and fsck.
- **Language:** java
- **Tags:** git, content-addressing, sha-256, merge-base, three-way-merge, dag, storage, oracle-test
- **Source round:** practice/java/20261118T2156Z
- **Tests:** known SHA-256 literals, flip-every-byte/truncate-every-length corruption loops, merge truth table, criss-cross merge base, seeded brute-force mergeBase/log/merge oracles with symmetry check. `mvn -q -B test`.
