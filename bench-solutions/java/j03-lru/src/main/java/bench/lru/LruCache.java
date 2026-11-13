package bench.lru;

import java.util.LinkedHashMap;
import java.util.Optional;

/** LRU cache with access-ordered LinkedHashMap. */
public final class LruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(16, 0.75f, true);

    public LruCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity < 1");
        this.capacity = capacity;
    }

    public Optional<V> get(K key) {
        return Optional.ofNullable(map.get(key));
    }

    public void put(K key, V value) {
        if (key == null || value == null) throw new IllegalArgumentException("null");
        map.put(key, value);
        if (map.size() > capacity) {
            var it = map.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    public int size() {
        return map.size();
    }
}
