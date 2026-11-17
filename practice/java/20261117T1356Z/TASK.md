# Task: primitive-array collections (level 12, Performance)

Overlap check: `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours) — none is a bitset or an open-addressing primitive map. No overlap.

Package `perf`. Standard library only.

## `IntBitSet` (fixed capacity, `long[]` words)
- `IntBitSet(int size)`: size >= 0 else IllegalArgumentException. `size()`.
- `set(i)`, `clear(i)`, `flip(i)`, `get(i)`; index outside `[0,size)` -> IndexOutOfBoundsException.
- `cardinality()`, `nextSetBit(from)` (-1 if none; `from` >= size returns -1, negative from -> IndexOutOfBounds).
- `rank(i)`: set bits in `[0,i)`, for `0 <= i <= size`.
- `select(k)`: index of the k-th (0-based) set bit, -1 if k >= cardinality, IllegalArgumentException if k < 0.
- `and/or/andNot(IntBitSet other)`: in place; sizes must be equal else IllegalArgumentException; return `this`.
- Equality by size+contents; `hashCode` consistent.

## `IntIntMap` (open addressing, linear probing, backward-shift delete, no boxing in hot paths)
- Any int key is legal (including 0 and Integer.MIN_VALUE).
- `put(k,v)` -> `OptionalInt` previous; `get(k)` -> `OptionalInt`; `getOrDefault`; `containsKey`; `remove(k)` -> `OptionalInt`; `addTo(k, delta)` returns new value (absent counts as 0; overflow throws ArithmeticException with the map unchanged); `size()`, `clear()`; `forEach(IntIntConsumer)`; `sortedKeys()`.
- Grows when load > 0.5; capacity stays a power of two.

## Success criteria
`mvn -q -B test` passes: unit tests, randomized differential tests against `java.util.BitSet` / `HashMap`, a clustered-hash-collision test, and time-bounded large-input tests.
