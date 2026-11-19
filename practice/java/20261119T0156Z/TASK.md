# Task: event-time windowed aggregation engine (`ae.stream`)

Level 20 capstone. Overlap check: `benchmark/java/` holds roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank,
counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue,
bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql. None is event-time stream
processing (watermarks, windows, lateness, checkpoints), so there is no overlap.

## Design
Events `Event(key, time, value)` arrive in arrival order (not time order). Engine keeps, per key, a `TreeMap<Window, Agg>`.

* `Window(start, end)`: start inclusive, end exclusive. `Agg(count, sum, min, max)` with `merge` (exact arithmetic).
* `WindowSpec` (sealed): `Tumbling(size)`, `Sliding(size, slide)` with `slide <= size`, `Session(gap)`.
  Windows are aligned to multiples of size/slide (floorMod, so negative times work). A session event has the proto window
  `[t, t+gap)`; proto windows that overlap **or touch** (`a.start <= b.end && b.start <= a.end`) merge.
* Watermark `wm = max(maxTime) - maxOutOfOrder` (saturating, monotonic, initially `Long.MIN_VALUE`).
* A window fires `ON_TIME` once `wm >= end`. It is purged once `end + allowedLateness <= wm` (saturating add).
* An event for a window checked against the watermark **before** the event itself:
  * `end + lateness <= prevWm` -> not applied; recorded as `Dropped(event, window)` (boundary: equal means dropped);
  * `end <= prevWm` (but not purged) -> applied and the updated window is emitted immediately as `LATE_UPDATE`;
  * otherwise applied silently.
  A session merge emits the **merged** window; a merge never extends a window that already fired into a still-open
  window silently: a merged window with `end > prevWm` simply fires again later as `ON_TIME` under its new bounds.
  Sessions merge only with live (non-purged) windows.
* `process` returns results sorted by (end, start, key, kind). `flush()` sets the watermark to `Long.MAX_VALUE`, fires
  everything remaining as `ON_TIME` and purges; afterwards every event is dropped.
* Failures are side-effect free: every window, aggregate and watermark is computed first (overflow throws
  `ArithmeticException`), then applied.
* `snapshot()` -> bytes (magic, version, config, watermark, state, dropped list, CRC32 trailer). `restore(bytes)` verifies
  CRC and structural invariants and throws `CorruptSnapshotException` otherwise. Snapshot bytes are canonical, so equal
  state gives equal bytes.

## Success criteria
* Literal tests for tumbling, sliding (incl. negative time), session (touching, bridging, late merge), lateness
  boundary (`end+lateness == wm` dropped, one less accepted), out-of-order bound, key independence, flush.
* Overflow and validation tests; failed `process` leaves `snapshot()` bytes unchanged.
* Randomised differential test (tumbling + sliding) against a brute-force oracle that recomputes the prefix watermark
  and the retained events naively; session oracle with unbounded lateness; invariant checker after every step.
* Checkpoint at random points, restore, continue: output and final snapshot equal an uninterrupted run.
* Snapshot truncation and flip-every-byte tests; re-sealed (valid CRC) corrupted images give exception or a valid engine.
