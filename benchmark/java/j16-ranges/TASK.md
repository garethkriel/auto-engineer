# j16-ranges: Set of long ranges

Java 21, standard library only. Put your solution under `bench-solutions/java/j16-ranges/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.ranges;
public final class LongRangeSet {
    public LongRangeSet();
    public void add(long from, long to);
    public void remove(long from, long to);
    public boolean contains(long x);
    public java.util.List<long[]> ranges();
    public long size();
}
```

- **Ranges:** every range is half-open, `[from, to)`. `from == to` is an empty range, and both `add` and `remove` ignore it. `from > to` throws `IllegalArgumentException`.
- **Adding and removing:** `add` merges overlapping and adjacent ranges, so `[1,3)` plus `[3,5)` becomes `[1,5)`. `remove` can split a range or remove several at once.
- **`ranges()`:** returns the current ranges as `{from, to}` arrays, sorted, disjoint and non-adjacent. It returns new arrays, so changing them doesn't change the set.
- **`size()`:** returns how many longs the set contains. If the count exceeds `Long.MAX_VALUE`, it returns `Long.MAX_VALUE`.
- **Extremes:** values all the way to `Long.MIN_VALUE` and `Long.MAX_VALUE` must work without overflow. Because ranges are half-open, `Long.MAX_VALUE` itself is never contained.
- **Performance:** it must stay fast with 200,000 separate ranges, at roughly logarithmic time per operation.
