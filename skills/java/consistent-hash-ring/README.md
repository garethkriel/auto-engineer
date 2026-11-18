# consistent-hash-ring

- **Description:** Immutable consistent-hash ring (`ae.ring.HashRing`) with weighted virtual nodes, injectable `Hasher`, `owner`, `preferenceList` (distinct replicas), `share()` arc fractions, bounded-load assignment (`assignBounded`, capacity ceil(c*K/N)) and `Rebalance.moves` for ring diffs.
- **Language:** java
- **Tags:** consistent-hashing, ring, virtual-nodes, replication, bounded-load, rebalancing, unsigned-order
- **Source round:** practice/java/20261118T1656Z
- **Reuse notes:** unsigned order via `hash ^ Long.MIN_VALUE`; unsigned arc length is plain `long` subtraction. Use a table `Hasher` in tests to pin exact positions, and real hashing for statistical properties (minimal movement, balance, weight proportionality). A node whose points all lose collisions is a member but unreachable.
