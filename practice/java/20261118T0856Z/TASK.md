# Task (level 17, larger design): event-sourced shopping cart with ports and adapters

Overlap check: `benchmark/java/` has j09-bank, j19-allocate, j32-fsm, j33-pricing, j24-queue. This task is an
event store + aggregate + read-model projection; none of those cores (accounts, allocation, FSM tables, pricing
rules, queues) is reproduced. Assumption: standard library only, in-memory adapter.

## Design
Packages and dependency direction (inward only): `adapter -> port <- app -> domain`.
- `ae.es.domain`: `CartEvent` (sealed: CartOpened, ItemAdded, ItemRemoved, CheckedOut), `Cart` aggregate.
  `Cart.decide*` methods validate and return new events (no mutation); `Cart.apply(event)` folds state.
  Rules: open once; qty > 0; a sku keeps the unit price of its first add (a different price -> IllegalArgumentException);
  remove only existing sku; checkout only a non-empty open cart; nothing after checkout (IllegalStateException).
  Totals use `Math.multiplyExact/addExact`.
- `ae.es.port`: `EventStore` (append with expected version, all-or-nothing; read stream; readAll from global position),
  `StoredEvent(position, streamId, version, event)`, `ConcurrencyException`.
- `ae.es.adapter`: `InMemoryEventStore` (thread safe, optimistic concurrency).
- `ae.es.app`: `CartService` (command handlers: load stream -> fold -> decide -> append) and `SalesProjection`
  (read model with a checkpoint; `catchUp(store)` is incremental and idempotent; `rebuild`).
Read model: per sku units sold and revenue cents over checked-out carts, plus count of open carts.

## Success criteria
- Unit tests for aggregate rules, store concurrency (stale version rejected, failed append stores nothing,
  global positions dense), service flow, projection (incremental == full rebuild == naive recompute from aggregates,
  randomised), concurrent appenders to different streams.
- `mvn -q -B clean test` passes.
