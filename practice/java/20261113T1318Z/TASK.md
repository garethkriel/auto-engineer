# Task (level 2, topic: classes and objects, constructors)
Implement `ae.Interval`: an immutable closed integer interval `[start, end]`.
- Constructor `Interval(int start, int end)`; throws IllegalArgumentException if start > end.
- Static factory `Interval.parse("[3,7]")` (whitespace tolerant; bad input -> IllegalArgumentException).
- `length()` (end-start+1, as long, no overflow), `contains(int)`, `overlaps(Interval)`, `intersection(Interval)` -> Optional, `merge(Interval)` -> Optional (present if overlapping or adjacent, e.g. [1,3]+[4,6]).
- Static `mergeAll(List<Interval>)` -> sorted, minimal list of disjoint non-adjacent intervals; input not mutated.
- equals/hashCode/toString (`[3,7]`).
Assumption: adjacency uses long arithmetic so Integer.MAX_VALUE doesn't overflow.
Success: `mvn -q -B test` passes.
