# j24-queue: Closeable bounded blocking queue

Java 21, standard library only. Put your solution under `bench-solutions/java/j24-queue/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.queue;
public final class BoundedQueue<E> {
    public BoundedQueue(int capacity);
    public boolean offer(E e, long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException;
    public E poll(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException;
    public void close();
    public int size();
}
```

- **Basics:** the queue is FIFO and holds at most `capacity` elements.
- **`offer`:** adds `e`. If the queue is full, it waits up to the timeout for space and returns `false` if none appears. A negative timeout counts as 0, which means don't wait.
- **`poll`:** removes and returns the head. If the queue is empty, it waits up to the timeout and returns `null` if nothing arrives.
- **`close()`:** idempotent.
  - Afterwards, `offer` always throws `IllegalStateException`, even when there's space. That includes producers already blocked in `offer`, which must wake promptly and throw.
  - `poll` still returns the remaining elements in order. Once the queue is empty, `poll` returns `null` **immediately**, without waiting. Consumers already blocked in `poll` must wake promptly.
- **Wake-ups:** a blocked `poll` must wake promptly when an element arrives, and a blocked `offer` must wake promptly when space frees up. No lost wake-ups.
- **Interrupts:** a thread interrupted while blocked gets `InterruptedException`.
- **Delivery:** under heavy concurrency, with several producers and consumers and a small capacity, every offered element is delivered exactly once.
- **Errors:** `capacity < 1`, a null element or a null `unit` throws `IllegalArgumentException`.
