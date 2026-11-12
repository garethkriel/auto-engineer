# j05-intervals: Merge intervals

Java 21, standard library only. Put your solution under `bench-solutions/java/j05-intervals/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.intervals;
public final class Intervals {
    public static List<int[]> merge(List<int[]> intervals);
}
```

- Each interval is `int[]{start, end}` with `start <= end`, and both ends are inclusive.
- Return the merged intervals sorted by start. Intervals that overlap or touch, such as `[1,3]` and `[3,5]`, merge into one.
- The input may be unsorted. Don't modify the input list or its arrays.
- These throw `IllegalArgumentException`:
  - a null list;
  - a null interval;
  - an array whose length isn't 2;
  - `start > end`.
