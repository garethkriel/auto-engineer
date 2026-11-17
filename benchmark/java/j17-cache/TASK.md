# j17-cache: Expiring LRU cache

Java 21, standard library only. Put your solution under `bench-solutions/java/j17-cache/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.cache;
public final class ExpiringCache<K, V> {
    public ExpiringCache(int capacity, java.time.Duration ttl, java.time.Clock clock);
    public java.util.Optional<V> get(K key);
    public void put(K key, V value);
    public int size();
}
```

- **Expiry:**
  - Read the time with `clock.instant()`.
  - An entry expires `ttl` after it was last `put`. It counts as expired once `now >= putTime + ttl`.
  - `get` doesn't extend an entry's life, but putting the same key again does.
- **Expired entries:** they're invisible. `get` returns `Optional.empty()` for them, and `size()` counts only live entries.
- **When a `put` takes the cache above `capacity` entries:**
  1. First, remove all expired entries.
  2. Then, if it's still over capacity, evict the least recently used live entry, where both `get` and `put` count as a use.
- **Thread safety:** it must be safe under concurrent `get` and `put`.
- **Errors:** each of these throws `IllegalArgumentException`:
  - `capacity < 1`;
  - a zero, negative or null `ttl`;
  - a null clock;
  - a null key or value.
