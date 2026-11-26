# Task: limit order book matching engine (`ae.book`)

Level 20 capstone (recorded level is capped at 10 by the validator). Overlap check: `benchmark/java/` names
(j01-roman .. j35-minisql: allocate, ratelimit, queue, pricing, ranked, ...) contain no order-matching task;
`skills/java` has `order-analytics` (reporting), not a matching engine.

## Model
Prices and quantities are positive `long`s (ticks / units). One instrument. Each order has a non-empty `owner` string.

## API (package `ae.book`)
- `enum Side {BUY, SELL}` with `opposite()`; `enum Tif {GTC, IOC, FOK}`; `enum Status {RESTING, FILLED, CANCELLED, REJECTED}`.
- `record Trade(long takerId, long makerId, long price, long qty)`; `price` is the maker's price.
- `record Result(Status status, List<Trade> trades, long filled, long leaves, String reason)`; `leaves = qty - filled`;
  `reason` is `""` unless noted below.
- `record Limit(long id, String owner, Side side, long price, long qty, Tif tif, boolean postOnly, long display)` with
  `Limit.of(id, owner, side, price, qty)` (GTC, not post-only, display 0) and withers `tif(Tif)`, `postOnly()`, `iceberg(long display)`.
- `OrderBook`: `Result submit(Limit)`, `Result market(long id, String owner, Side side, long qty)`, `boolean cancel(long id)`,
  `boolean amendQty(long id, long newQty)`, `Result replace(long id, long newPrice, long newQty)`,
  `OptionalLong bestBid()`, `OptionalLong bestAsk()`, `OptionalLong leaves(long id)`,
  `List<Level> depth(Side side, int levels)` (`record Level(long price, long visibleQty, int orders)`, best price first),
  `int size()`, `void checkInvariants()` (throws `IllegalStateException`).

## Rules
1. Matching: a taker matches opposite orders at crossing prices (buy: ask <= limit; sell: bid >= limit; market: any), best price
   first, FIFO inside a price level, at the maker's price. A trade is `min(taker remaining, maker visible)`.
2. Iceberg (`display > 0`): only `min(display, total)` is visible. When the visible slice is used up and hidden quantity
   remains, the slice reloads to `min(display, hidden)` and the order goes to the BACK of its level. `depth` counts visible quantity only.
3. After matching: remaining 0 -> `FILLED`. Otherwise self-trade stop -> `CANCELLED` reason `SELF_TRADE`; market -> `CANCELLED`
   reason `MARKET`; IOC -> `CANCELLED` reason `IOC`; GTC limit -> rests, `RESTING` (visible = `min(display,leaves)` if iceberg).
4. Self-trade prevention: if the next maker to be matched has the taker's owner, matching stops, trades already made stay,
   the maker is untouched and the taker's remainder is cancelled (rule 3).
5. Post-only: if the order would match anything at all (best opposite price crosses), `REJECTED` reason `POST_ONLY`, nothing changes.
6. FOK: scan crossing levels best first, adding each level's TOTAL quantity (including hidden). If the running total reaches `qty`
   before reaching a level that contains an order of the taker's owner, it is fillable and matching proceeds normally.
   A level holding an own-owner order ends the scan as "not fillable"; unfillable -> `REJECTED` reason `FOK`, nothing changes.
7. `amendQty(id, n)`: unknown id -> false. `n < total` reduces in place (priority kept, visible = min(visible, n));
   `n == total` is a no-op; `n > total` sets total = n, visible = display>0 ? min(display,n) : n and moves the order to the back. Returns true.
8. `replace(id, p, q)`: unknown id -> `NoSuchElementException`; otherwise validate, remove the order and submit a fresh GTC limit
   with the same id, owner, side and display (never post-only) at `p`/`q`; it may trade.
9. Invalid input (`qty <= 0`, `price <= 0`, negative display, blank owner, null args, id of a live order) throws
   `IllegalArgumentException`/`NullPointerException` BEFORE any state changes.
10. Ids may be reused once the order is no longer live.

## Success criteria
- `mvn -q -B clean test` passes: hand-derived literal cases for every rule, plus a seeded differential test against an
  independent naive oracle (list + sort per match) comparing every `Result`, the visible depth and `leaves` of all ids after
  each operation, with `checkInvariants()` called after every operation.
- A mutation pass (`mutate.py`) over the main source kills every mutant.
