# j23-segtree: Range add, range sum, range min

Java 21, standard library only. Put your solution under `bench-solutions/java/j23-segtree/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.segtree;
public final class RangeArray {
    public RangeArray(long[] initial);
    public void add(int from, int to, long delta);   // adds delta to every element in [from, to)
    public long sum(int from, int to);              // sum of [from, to); 0 for an empty range
    public long min(int from, int to);              // minimum of [from, to)
    public int size();
}
```

- **Ranges and bounds:**
  - All ranges are half-open, `[from, to)`, with `0 ≤ from ≤ to ≤ size()`. Anything outside that throws `IllegalArgumentException`.
  - `min` of an empty range throws `IllegalArgumentException`.
  - `add` on an empty range does nothing.
- **The array:** the constructor copies `initial`, so later changes to the caller's array have no effect. A null array throws `IllegalArgumentException`. A zero-length array is allowed.
- **Values:** sums stay within `long` in the tests.
- **Performance:** one million elements with one million mixed `add`, `sum` and `min` operations must finish within a few seconds. That needs logarithmic time per operation.
