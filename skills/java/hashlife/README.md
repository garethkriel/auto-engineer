# hashlife

- **Description:** Conway's Game of Life (B3/S23) with Gosper's HashLife: hash-consed immutable quadtree nodes, memoised "centre half after 2^j generations", arbitrary-length `step(n)` by binary decomposition (2^40 generations of a glider in milliseconds), `compact()` to drop unreachable nodes, an invariant checker, an RLE parser/writer and a naive oracle.
- **Language:** java (standard library only)
- **Tags:** cellular-automaton, quadtree, hash-consing, memoisation, rle, differential-testing
- **Source round:** practice/java/20261125T0456Z
- **Reusable ideas:** canonical interning with identity-keyed records; memo arrays on immutable nodes; a "padded" check before advancing so growth never clips; test hash-consing sharing with a periodic fixture vs random control after `compact()`.
- **Tests:** `mvn -q -B test` (35 tests: RLE, published Life facts, naive differential, additivity, invariant corruption).
