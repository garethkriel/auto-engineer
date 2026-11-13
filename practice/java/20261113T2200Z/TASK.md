# Task (level 3, topic: List sorting with Comparator, Math)
Implement `ae.Intervals` with a `record Interval(int start, int end)` (closed, start <= end, else IllegalArgumentException).
- `static List<Interval> merge(List<Interval>)`: sort and merge intervals that overlap or touch (end+1 >= next start, i.e. [1,3]+[4,5] -> [1,5]); input unmodified; null -> NPE.
- `static long coveredLength(List<Interval>)`: count of integer points covered (end-start+1 each, after merging), must not overflow int.
- `static List<Interval> gaps(List<Interval>, Interval window)`: uncovered sub-intervals inside window.
Assumption: touching means adjacent integers, since intervals are closed integer ranges.
Success: `mvn -q -B test` passes.
