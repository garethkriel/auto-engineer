# 20261118T0456Z — Level 16: indexable skip list (`ae.skip.SkipListMap`)

## Overlap check
Benchmark names (j03-lru, j17-cache, j23-segtree, j24-queue, j05-intervals, ...) are caches, queues and a segment tree.
A probabilistic ordered map with navigation and order statistics matches none of them (names only; hidden tests never read).

## Task
Implement a sorted map as an *indexable skip list* in `ae.skip`:

```java
public final class SkipListMap<K, V> implements Iterable<SkipListMap.Entry<K, V>> {
    public record Entry<K, V>(K key, V value) {}
    public SkipListMap();                                  // natural order, seed 1
    public SkipListMap(Comparator<? super K> cmp, long seed); // seed makes levels deterministic
    public V put(K key, V value);        // returns previous value or null
    public V get(K key);                 // null if absent
    public boolean containsKey(K key);
    public V remove(K key);              // returns removed value or null
    public int size(); public boolean isEmpty(); public void clear();
    public Optional<Entry<K,V>> first(); last();
    public Optional<Entry<K,V>> floor(K k);    // greatest <= k
    public Optional<Entry<K,V>> ceiling(K k);  // least >= k
    public Optional<Entry<K,V>> lower(K k);    // greatest < k
    public Optional<Entry<K,V>> higher(K k);   // least > k
    public int rank(K key);              // number of keys strictly less than key (key need not be present)
    public Entry<K,V> select(int index); // index-th smallest (0-based); IndexOutOfBoundsException otherwise
    public List<Entry<K,V>> range(K fromInclusive, K toExclusive); // ascending; empty if from >= to
    public int countRange(K fromInclusive, K toExclusive);         // O(log n) via ranks
    public Iterator<Entry<K,V>> iterator();                        // ascending, fail-fast (ConcurrentModificationException)
}
```
Null keys -> NullPointerException. Null values allowed in `put` is NOT allowed (NPE) so `get == null` means absent.
Rank/select/countRange must run in expected O(log n): every forward link stores a *span* (number of level-0 steps it skips).

## Success criteria
- `mvn -q -B clean test` passes.
- Randomised differential test against `java.util.TreeMap` (put/remove/navigation/rank/select/range) over several seeds.
- A package-private `checkInvariants()` verifies ordering at every level, spans, size and level height after each mutation in tests.
- Expected height stays logarithmic (asserted loosely for 100k keys).

## Assumptions
Level probability 1/2, max level 32, `SplittableRandom(seed)`. Single-threaded; not thread-safe.
