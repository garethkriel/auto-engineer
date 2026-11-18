package bench.lru;

import java.util.LinkedHashMap;
import java.util.Optional;

/** Thread-safe generic LRU cache. */
public final class LruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>();

    public LruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    public synchronized Optional<V> get(K key) {
        if (key == null) {
            return Optional.empty();
        }
        V v = map.remove(key);
        if (v == null) {
            return Optional.empty();
        }
        map.put(key, v);
        return Optional.of(v);
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("null key or value");
        }
        map.remove(key);
        map.put(key, value);
        if (map.size() > capacity) {
            var it = map.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized int size() {
        return map.size();
    }
}
