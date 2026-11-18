# skip-list-map

- **Description:** Indexable skip list sorted map (`ae.skip.SkipListMap`). Every forward link stores a span, giving expected O(log n) `rank`, `select`, `countRange` besides put/get/remove, floor/ceiling/lower/higher, half-open `range`, fail-fast iteration, seeded deterministic levels and a `checkInvariants()` hook.
- **Language:** java
- **Tags:** skip-list, ordered-map, order-statistics, rank-select, spans, data-structure, differential-testing
- **Source round:** practice/java/20261118T0456Z (level 16)
- **Pitfalls:** keep header spans at `size + 1` for every unused level and update them on every insert/remove; rank = position of last smaller node; tests compare against `TreeMap` after every mutation plus invariant check.
