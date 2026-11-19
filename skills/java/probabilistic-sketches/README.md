# probabilistic-sketches

- **Description:** `ae.sketch`: seeded `Hash64` (FNV-1a + splitmix64 finaliser), `BloomFilter` (double hashing, textbook sizing, union, cardinality estimate), `CountMinSketch` (plain and conservative update, one-sided error, overflow-safe add/merge), `HyperLogLog` (p 4..16, linear-counting small range, register-max merge), and CRC32-sealed serialization shared through `Codec`.
- **Language:** Java 21, standard library only
- **Tags:** bloom-filter, count-min, hyperloglog, sketch, hashing, serialization, seeded-tests
- **Source round:** practice/java/20261119T1756Z
- **Reusable ideas:** test one-sided guarantees (no false negatives, never underestimate) exactly and statistical ones against seeded data with a bound plus a lower sanity bound; merge algebra (equals sketch of union, commutative, idempotent); exhaustive truncation and single-byte-flip loops, plus crafted re-sealed images (`Codec.seal`) to test structural validation; stray bits beyond the filter size and out-of-range HLL ranks are rejected on load.
- **Run tests:** `mvn -q -B test`
