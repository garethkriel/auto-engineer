# order-book

- **Description:** single-instrument limit order book matching engine (`ae.book`): price-time priority at the maker's price,
  iceberg orders whose reloaded slice goes to the back of the queue, IOC / FOK (hidden quantity counted, own-owner level ends the
  scan) / post-only, self-trade prevention that cancels the taker remainder, `amendQty` (reduce keeps priority, increase loses it),
  `replace`, visible-only `depth`, and `checkInvariants()`.
- **Language:** java (JDK 21, JUnit 5, standard library only)
- **Tags:** matching-engine, order-book, iceberg, self-trade-prevention, differential-test, invariants
- **Source round:** `practice/java/20261126T1956Z` (rules in its `TASK.md`)
- **Tests:** 47 (28 literal rule cases, 5 differential/oracle tests, 14 invariant-corruption tests). The round's `mutate.py`
  killed 26/26 mutants.
- **Reuse notes:** keep a flat-list oracle that re-sorts per match as the differential reference; build corrupt states by
  reflection, one per invariant rule; record components must not share a name with a same-arity wither method.
