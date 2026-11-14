# Task: order analytics with custom collectors and composable rules (level 7)

Overlap check vs `benchmark/java/` (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex): none; this is an order-analytics domain with a custom `Collector` and predicate combinators, not generic stream drills.

Package `ae`. Assumption: money uses `BigDecimal`; all results are immutable.

- `record Order(String customer, String category, BigDecimal amount, LocalDate date)`; constructor rejects nulls and negative amounts.
- `record Stats(long count, BigDecimal sum, BigDecimal min, BigDecimal max)` with `Stats.EMPTY`, `accept(BigDecimal)` returning a new Stats, `combine(Stats)`, `mean()` -> `Optional<BigDecimal>` (scale 2, HALF_UP).
- `Analytics`:
  - `static Collector<Order, ?, Stats> statsCollector()` custom collector (via `Collector.of`, supports parallel combine).
  - `Map<String, Stats> statsByCustomer(List<Order>)` (sorted by key, TreeMap).
  - `Optional<String> topCategory(List<Order>, String customer)`: category with highest total for the customer; ties -> alphabetically first.
  - `Map<Boolean, List<Order>> splitAtThreshold(List<Order>, BigDecimal)`: partition amount >= threshold (true) vs below.
  - `Map<YearMonth, BigDecimal> monthlyTotals(List<Order>)` sorted by month.
- `Rules`: `static <T> Predicate<T> allOf(List<Predicate<T>>)`, `anyOf`, `not`-free `noneOf`; empty allOf is true, empty anyOf false, empty noneOf true. Returned predicates must not be affected by later mutation of the input list.

Success: `mvn -q -B test` passes.
