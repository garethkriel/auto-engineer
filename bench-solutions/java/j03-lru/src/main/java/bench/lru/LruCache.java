package bench.lru;

import java.util.LinkedHashMap;
import java.util.Optional;

/** Generic LRU cache. */
public final class LruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>();

    public LruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity < 1");
        }
        this.capacity = capacity;
    }

    public Optional<V> get(K key) {
        V v = map.remove(key);
        if (v == null) {
            return Optional.empty();
        }
        map.put(key, v);
        return Optional.of(v);
    }

    public void put(K key, V value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("null key or value");
        }
        map.remove(key);
        if (map.size() >= capacity) {
            K eldest = map.keySet().iterator().next();
            map.remove(eldest);
        }
        map.put(key, value);
    }

    public int size() {
        return map.size();
    }
}
