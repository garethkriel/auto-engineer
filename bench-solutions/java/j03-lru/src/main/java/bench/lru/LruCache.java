package bench.lru;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class LruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>();

    public LruCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    public Optional<V> get(K key) {
        V v = map.remove(key);
        if (v == null) return Optional.empty();
        map.put(key, v);
        return Optional.of(v);
    }

    public void put(K key, V value) {
        if (key == null || value == null) throw new IllegalArgumentException("null");
        map.remove(key);
        map.put(key, value);
        if (map.size() > capacity) {
            var it = map.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    public int size() { return map.size(); }
}
