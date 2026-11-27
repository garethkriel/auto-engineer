package bench.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Thread-safe LRU cache whose entries expire a fixed time after their last put. */
public final class ExpiringCache<K, V> {
    private record Entry<V>(V value, Instant expiresAt) {}

    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<K, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);

    public ExpiringCache(int capacity, Duration ttl, Clock clock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("ttl");
        if (clock == null) throw new IllegalArgumentException("clock");
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = clock;
    }

    private boolean expired(Entry<V> e, Instant now) {
        return e.expiresAt() != null && !now.isBefore(e.expiresAt());
    }

    public synchronized Optional<V> get(K key) {
        if (key == null) throw new IllegalArgumentException("key");
        Instant now = clock.instant();
        Entry<V> e = map.get(key);
        if (e == null) return Optional.empty();
        if (expired(e, now)) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(e.value());
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) throw new IllegalArgumentException("null key or value");
        Instant now = clock.instant();
        Instant exp;
        try {
            exp = now.plus(ttl);
        } catch (RuntimeException ex) {
            exp = null; // beyond representable range: never expires
        }
        map.remove(key);
        map.put(key, new Entry<>(value, exp));
        if (map.size() > capacity) {
            map.values().removeIf(en -> expired(en, now));
            Iterator<K> it = map.keySet().iterator();
            while (map.size() > capacity && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    public synchronized int size() {
        Instant now = clock.instant();
        map.values().removeIf(en -> expired(en, now));
        return map.size();
    }
}
