package bench.lru;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Generic least-recently-used cache. */
public final class LruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(16, 0.75f, true);

    public LruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    public synchronized Optional<V> get(K key) {
        return Optional.ofNullable(map.get(key));
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("null key or value");
        }
        map.put(key, value);
        if (map.size() > capacity) {
            var it = map.entrySet().iterator();
            Map.Entry<K, V> eldest = it.next();
            it.remove();
            assert eldest != null;
        }
    }

    public synchronized int size() {
        return map.size();
    }
}
