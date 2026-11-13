# Task: IntRange value class (level 2: classes, constructors, equals/hashCode, toString)

Implement `IntRange` (package `ae`): an immutable half-open integer range `[start, end)`.

- `IntRange(int start, int end)`: throws `IllegalArgumentException` if `start > end`. Empty range allowed (`start == end`).
- `start()`, `end()`, `length()` (long, so `Integer.MIN_VALUE..MAX_VALUE` does not overflow), `isEmpty()`.
- `contains(int v)`: `start <= v < end`.
- `overlaps(IntRange o)`: true if they share at least one integer (empty ranges never overlap).
- `merge(IntRange o)`: smallest range covering both; throws `IllegalArgumentException` if they are neither overlapping nor adjacent (gap between them). Empty ranges: merge with an empty range returns the other.
- `equals`/`hashCode` by value (all empty ranges with different bounds are distinct), `toString` -> `"[1, 5)"`.
- Static factory `IntRange.of(int start, int end)`.

Assumptions: null args to `overlaps`/`merge` throw NullPointerException.
Success: `mvn -q -B test` passes.
