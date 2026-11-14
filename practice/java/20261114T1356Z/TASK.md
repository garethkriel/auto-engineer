# Task
Package `pc`: immutable persistent collections with structural sharing.
- `PersistentStack<T>`: `empty()`, `push(T)`, `pop()` (returns stack without top; IllegalStateException if empty), `peek()` (Optional), `size()`, `isEmpty()`, `Iterable<T>` top-first, `stream()`, `equals/hashCode/toString`. Nulls rejected (NPE). `push` shares the old stack as its tail (`pop()` of a pushed stack returns the same instance).
- `PersistentQueue<T>`: FIFO with amortised O(1) `enqueue`, `dequeue()` returning `Dequeued<T>(T head, PersistentQueue<T> rest)` (IllegalStateException if empty), `peek()`, `size()`, iteration oldest-first, equals/hashCode by element sequence.
- Old versions are never changed by operations. Size must not overflow stack for 100k elements (no recursion).
Assumptions: nulls disallowed; equality is by element sequence.
Success: `mvn -q -B test` passes.
