# j03-lru: Generic LRU cache

Java 21, standard library only. Put your solution under `bench-solutions/java/j03-lru/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.lru;
public final class LruCache<K, V> {
    public LruCache(int capacity);
    public Optional<V> get(K key);
    public void put(K key, V value);
    public int size();
}
```

- The cache holds at most `capacity` entries. Adding a new key when it is full evicts the least-recently-used entry.
- Both `get` of a present key and `put` count as a use. `put` on an existing key replaces its value.
- `get` of a missing key returns `Optional.empty()`.
- `capacity < 1` throws `IllegalArgumentException`, and so does `put` with a null key or null value.
