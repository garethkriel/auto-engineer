# Task 20261119T0856Z: bitemporal store (`ae.bitemporal`)

Level 20 capstone. A small in-memory **bitemporal** key/value store: every fact has a *valid time*
(when it is true in the world, a half-open interval `[from, to)` of `long`s) and a *transaction time*
(when the store learned it). Corrections never destroy knowledge: you can always ask
"what did we believe at transaction T about valid time V?".

Overlap check against `benchmark/java/`: j09-bank (accounts), j29-kvlog (crash-safe KV file), j17-cache,
j35-minisql. None has valid-time intervals, as-of queries across two time axes or revision history. Not overlapping.

## Assumptions
- No persistence. The store is in memory and thread-safe through `synchronized` on the store.
- `FOREVER = Long.MAX_VALUE` means "open ended". Valid intervals need `from < to`.
- Transaction ids are 1, 2, 3, ... ; tx `0` is the empty store before any write.
- Transaction timestamps come from an injected `LongSupplier` and must be non-decreasing.

## Design
- `Segment<V>(from, to, value)`: record, `from < to`, value non-null.
- `Timeline<V>`: immutable, sorted, disjoint segments; adjacent segments with equal values are always
  coalesced (so equality of timelines is structural). `valueAt`, `assign(from,to,v)`, `clear(from,to)`,
  `segments()`.
- `Delta<V>(from, to, before, after)`: a valid-time piece where two knowledge states differ; `null` = no value.
- `Revision<V>(tx, timestamp, value)`: a change in belief about one valid instant; `value == null` = unknown.
- `BitemporalStore<K extends Comparable<K>, V>`:
  - `long assertValue(key, from, to, value)`: value holds on `[from,to)`; overwrites what was there.
  - `long retract(key, from, to)`: no value on `[from,to)`.
  - `long transact(Consumer<Tx<K,V>>)`: several operations on several keys as ONE transaction; later ops see
    earlier ones. If the consumer throws, nothing is committed. Using the `Tx` after it returns throws
    `IllegalStateException`.
  - A transaction whose net effect changes nothing records **nothing** and returns the current tx id.
  - `long currentTx()`; `long txAtTime(ts)`: latest tx with timestamp `<= ts`, 0 if none.
  - `Optional<V> valueAt(key, validTime, tx)` and `valueAt(key, validTime)` (latest).
  - `List<Segment<V>> history(key, tx)` / `history(key)`.
  - `SortedMap<K,V> snapshot(validTime, tx)`: every key with a value at that valid time.
  - `List<Delta<V>> diff(key, txA, txB)`: sorted, coalesced, only differing pieces.
  - `List<Revision<V>> revisions(key, validTime)`: how belief about that instant changed over tx time;
    consecutive equal beliefs are not repeated; starts at the first tx that gave it a value.
- Failure atomicity: validate and compute everything into locals before publishing a transaction.
- Argument errors: `IllegalArgumentException` (null, `from >= to`, tx outside `0..currentTx`);
  clock going backwards: `IllegalStateException`, nothing recorded.

## Success criteria
- Literal hand-derived tests for overwrite splitting, coalescing, retract, as-of reads, diff, revisions,
  transaction atomicity, no-op not recorded, clock fencing.
- A seeded randomized differential test against a brute-force oracle (an array per tx over a small valid-time
  domain) that also runs an invariant checker (sorted, disjoint, coalesced) after every transaction.
- `mvn -q -B clean test` passes.
