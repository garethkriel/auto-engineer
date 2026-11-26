package bench.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Thread-safe LRU cache whose entries expire a fixed time after their last put. */
public final class ExpiringCache<K, V> {
    private record Entry<V>(V value, Instant expiresAt) {
    }

    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<K, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);

    public ExpiringCache(int capacity, Duration ttl, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock");
        }
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized Optional<V> get(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key");
        }
        Entry<V> e = map.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (expired(e, clock.instant())) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(e.value());
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("null key or value");
        }
        Instant now = clock.instant();
        map.put(key, new Entry<>(value, now.plus(ttl)));
        if (map.size() > capacity) {
            removeExpired(now);
            if (map.size() > capacity) {
                Iterator<K> it = map.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    public synchronized int size() {
        Instant now = clock.instant();
        removeExpired(now);
        return map.size();
    }

    private void removeExpired(Instant now) {
        map.entrySet().removeIf(en -> expired(en.getValue(), now));
    }

    private static boolean expired(Entry<?> e, Instant now) {
        return !now.isBefore(e.expiresAt());
    }
}
