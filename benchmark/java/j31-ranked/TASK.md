# j31-ranked: Order-statistic multiset

Java 21, standard library only. Put your solution under `bench-solutions/java/j31-ranked/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.ranked;
public final class RankedMultiset<E> {
    public RankedMultiset(java.util.Comparator<? super E> cmp);
    public void add(E e);
    public boolean remove(E e);
    public int size();
    public int count(E e);
    public int rank(E e);
    public E select(int index);
    public int countBetween(E lo, E hi);
    public java.util.List<E> toList();
}
```

- **Order:** a sorted multiset ordered by `cmp`. Two elements are equal when `cmp` returns 0; nothing else (`equals`, `hashCode`) is used.
- **Operations:**
  - `add` inserts one occurrence.
  - `remove` deletes one occurrence of an equal element and returns whether there was one.
  - `size` counts all occurrences.
  - `count(e)` is the number of elements equal to `e`.
  - `rank(e)` is the number of elements strictly less than `e`. `e` need not be present.
  - `select(i)` returns the element at 0-based position `i` in sorted order. Among equal elements, any one of them may be returned. It throws `IndexOutOfBoundsException` unless `0 <= i < size()`.
  - `countBetween(lo, hi)` is the number of elements `x` with `lo <= x < hi`. It is 0 when `lo >= hi`.
  - `toList()` returns a new list of every occurrence in sorted order. Later changes to the multiset don't affect it.
- **Errors:** a null comparator or a null element argument throws `IllegalArgumentException`.
- **Performance:** every operation except `toList` must take O(log n) time, even for sorted insertion orders and heavy duplicates. The tests use hundreds of thousands of elements and operations under time limits of a few seconds.
