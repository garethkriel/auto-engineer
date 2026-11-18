# Task: indexed d-ary priority queue with change-key (level 16)

Overlap check: `benchmark/java/` has j24-queue (blocking bounded queue) and j31-ranked (order-statistic multiset); neither is a heap with handles. Not copied.

Implement `ae.pq.IndexedPriorityQueue<K>`, a min-priority queue over integer ids `0..capacity-1`, each id holding at most one key.

- `IndexedPriorityQueue(int capacity, Comparator<? super K> order, int arity)`; capacity >= 0, arity in 2..16, else `IllegalArgumentException`; nulls -> `NullPointerException`.
- `void insert(int id, K key)`: id out of range -> `IndexOutOfBoundsException`; already present -> `IllegalStateException`; null key -> NPE.
- `boolean contains(int id)`, `K keyOf(int id)` (absent -> `NoSuchElementException`).
- `void changeKey(int id, K key)`: raise or lower key in O(log n); absent -> `NoSuchElementException`.
- `K remove(int id)`: remove arbitrary id, return its key; absent -> `NoSuchElementException`.
- `int peekId()`, `K peekKey()`, `int pollId()`: empty -> `NoSuchElementException`.
- `int size()`, `boolean isEmpty()`, `void clear()`.
- Ties between equal keys are broken by smaller id, so the order is fully deterministic.
- A failed call (including a throwing comparator) must leave the queue unchanged where it can be detected before mutation.

Success: `mvn -q -B test` passes, including a randomized differential test against a TreeSet model for several arities, and invariants checked after every operation.

Assumption: comparator exceptions mid-sift are not required to be atomic (documented).
