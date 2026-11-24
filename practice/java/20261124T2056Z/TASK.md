# Task: order-statistic left-leaning red-black tree map (`ae.rbt`)

Level 20 capstone (small end-to-end data structure with checker, oracle and mutation check). Language: Java 21, JUnit 5, standard library only.

## Overlap check
Listed `benchmark/java/` ids: j01-roman ... j35-minisql. Closest is j23-segtree (range sums over an array); this task is a balanced ordered *map* with rank/select, so the core algorithm differs. Existing skills `skip-list-map` and `persistent-collections` are different structures. Assumption recorded: no overlap.

## API (`ae.rbt.RbTreeMap<K,V>`)
- `RbTreeMap()` natural ordering; `RbTreeMap(Comparator<? super K>)`.
- `V put(K,V)` returns previous value or null. Null keys/values -> NPE. If the comparator treats a key as equal to an existing one, the value is replaced and the ORIGINAL key object is kept.
- `V get(K)`, `boolean containsKey(K)`, `V remove(K)` (previous value or null), `int size()`, `boolean isEmpty()`.
- `K firstKey()`, `K lastKey()` -> `NoSuchElementException` when empty. `Optional<K> floorKey/ceilingKey/lowerKey/higherKey(K)`.
- `Optional<Map.Entry<K,V>> pollFirst()` removes and returns the smallest entry.
- `int rank(K)`: number of stored keys strictly less than K (K need not be stored).
- `K select(int i)`: key with 0-based sorted index i, else `IndexOutOfBoundsException`.
- `int countRange(K lo, K hi)`: stored keys k with lo <= k <= hi; 0 if lo > hi.
- `List<Map.Entry<K,V>> entries(K lo, K hi)`: those entries in ascending order (inclusive); `List<K> keys()` all keys ascending.
- Package-private: `void check()` throws `IllegalStateException` whose message starts with one of `root-red`, `red-right`, `double-red`, `black-height`, `order`, `size`; `int height()`; `Node` class and `static of(Comparator, Node)` for hand-built corrupt trees.

## Design
Sedgewick left-leaning red-black tree (2-3 variant), recursive put/delete with rotateLeft/rotateRight/flipColors/moveRedLeft/moveRedRight/balance. Each node stores subtree size, updated in every rotation and on the way back up, which gives O(log n) rank/select/countRange. Height bound: <= 2*log2(n+1).

## Success criteria
`mvn -q -B clean test` passes, including: literal known answers, comparator semantics, a checker test class with one corrupt tree per rule, randomized differential tests against `TreeMap` with `check()` after every mutation, a height bound test on 100k sequential inserts, and a mutation run proving the tests kill rule-dropping mutants.
