# primitive-collections

- **Description:** `perf.IntBitSet` (fixed-size `long[]` bit set with rank/select/nextSetBit/and/or/andNot) and `perf.IntIntMap` (open-addressing int-to-int map: linear probing, backward-shift delete, load <= 0.5, `used[]` flags so every int is a legal key, `addTo` with overflow-safe update).
- **Language:** java
- **Tags:** performance, bit manipulation, primitive arrays, open addressing, no boxing, differential testing
- **Source round:** practice/java/20261117T1356Z
- **Verified:** `mvn -q -B test` passes (17 tests).

Reusable ideas: backward-shift deletion condition (a later entry moves into the hole iff its home slot is not cyclically within (hole, j]); crafting colliding keys by mirroring the hash in the test; differential tests against `java.util.BitSet`/`HashMap`; `assertTimeoutPreemptively` for large-input checks.
