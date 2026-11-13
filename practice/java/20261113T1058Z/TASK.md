# Task: `Interval` class (level 2: classes and objects, static vs instance)

Implement `ae.Interval`, an immutable closed integer interval `[low, high]`.

- `Interval.of(low, high)` static factory; throws `IllegalArgumentException` if `low > high`. Constructor is private.
- Instance: `low()`, `high()`, `length()` (= high - low, as long, overflow-safe), `contains(int)`, `overlaps(Interval)` (closed: touching endpoints overlap), `merge(Interval)` (smallest interval covering both; only if they overlap or are adjacent i.e. `a.high + 1 >= b.low`, otherwise `IllegalArgumentException`).
- Static: `Interval.mergeAll(List<Interval>)` returns a sorted, minimal list of disjoint, non-adjacent intervals (input not mutated; null -> NPE); `Interval.createdCount()` counts instances ever created (static counter, thread-safe).
- `equals`/`hashCode`/`toString` (`[1, 5]`), `compareTo` by low then high.

Assumption: adjacency means integers with no gap, so `[1,3]` and `[4,6]` merge.
Success: `mvn -q -B test` passes.
