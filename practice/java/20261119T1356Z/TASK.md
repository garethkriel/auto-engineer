# Task: `ae.gc` simulated heap with allocator, mark-sweep GC and compaction (level 20 capstone)

Overlap check: `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). `allocate` is resource allocation, not a memory allocator; none is a heap/GC. No overlap assumed (recorded assumption).

## Design
`Heap` simulates a word-addressed memory `int[capacity]` and manages objects through stable integer **handles** (never reused, start at 1).

Block layout at address `a`: `[handle][data words ...][ref slots ...]`; block size = `1 + dataWords + refSlots`. Ref slots hold a handle or `0` (null).

* Free space: address-ordered extents, always coalesced (no two adjacent, none empty).
* `FitPolicy.FIRST_FIT`: lowest-address extent with length >= need. `BEST_FIT`: smallest such length, ties to lowest address. The block is carved from the **start** of the chosen extent.
* `alloc(dataWords, refSlots)`: arguments must be >= 0 (else `IllegalArgumentException`); block bigger than capacity -> `HeapFullException`. Data words start 0, refs null. If no extent fits and `autoCollect` is on: run `gc()`, retry; then `compact()`, retry; otherwise throw `HeapFullException`. A failed alloc leaves live objects untouched (a gc/compaction that already ran stays).
* Roots: `addRoot(h)` / `removeRoot(h)` (multiset not required; a set). Roots must be live handles.
* `gc()`: iterative mark from roots (cycles are fine), then sweep every unmarked object: free its extent with coalescing, drop it. Returns `GcResult(freedObjects, freedWords)`. Reclaimed handles are reported in ascending order to every registered `FreeListener` after the heap is consistent again.
* `compact()`: slides all live blocks to the lowest addresses preserving address order, updates addresses, moves memory content; handles stay valid. Returns number of objects that moved. Afterwards at most one free extent exists, at the end.
* Access: `getData/setData(h, i, v)`, `getRef/setRef(h, i, target)` (target `0` or live handle). Dead/unknown handle -> `IllegalStateException`; bad index -> `IndexOutOfBoundsException`.
* `isLive(h)`, `liveHandles()` (ascending), `addressOf(h)`, `sizeOf(h)`.
* `stats()` -> `Stats(capacity, usedWords, freeWords, freeExtents, largestFree, liveObjects)`. `usedWords + freeWords == capacity` always.
* `checkInvariants()` (package-private): blocks and extents tile `[0,capacity)` exactly, no adjacent free extents, header words equal handles, every ref slot is 0 or a live handle.

## Success criteria
* Literal placement tests for first fit, best fit and tie-breaking, coalescing in all neighbour cases.
* gc: unreachable cycles are freed, reachable ones kept, chains of depth 100000 do not overflow the stack.
* compact: content and references preserved; handles stable.
* Seeded randomised differential test against a naive oracle (maps of handle -> data/refs, reachability by BFS) with invariants after every step; mutation-resistant.
* `mvn -q -B clean test` passes.
