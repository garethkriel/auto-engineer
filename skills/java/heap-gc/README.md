# heap-gc

- **Language:** Java 21 (standard library only)
- **Tags:** garbage-collection, mark-sweep, compaction, free-list, allocator, first-fit, best-fit, invariant-checker
- **Source round:** practice/java/20261119T1356Z

Simulated word heap `ae.gc.Heap`: stable integer handles, coalescing address-ordered free list (first/best fit, ties to lowest address), iterative mark-sweep (cycles, 100k-deep chains), sliding compaction that preserves content and refs, optional auto gc-then-compact on allocation failure, free listeners called after the heap is consistent, and a package-private `checkInvariants()` tiling checker.

Reuse ideas: TreeMap extent coalescing (check lower and exact-next neighbours); keep metadata outside the memory array and validate with a tiling checker; seeded differential test against a reachability oracle that reconciles after auto-collection.
Tests: `mvn -q -B test` (24 tests).
