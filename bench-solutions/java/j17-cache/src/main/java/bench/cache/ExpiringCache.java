package bench.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ExpiringCache<K, V> {
    private record Entry<V>(V value, Instant expiresAt) {}

    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<K, Entry<V>> map = new LinkedHashMap<>(); // eldest = least recently used

    public ExpiringCache(int capacity, Duration ttl, Clock clock) {
        if (capacity < 1 || ttl == null || ttl.isZero() || ttl.isNegative() || clock == null) {
            throw new IllegalArgumentException("invalid argument");
        }
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = clock;
    }

    private boolean expired(Entry<V> e, Instant now) {
        return e.expiresAt() != null && !now.isBefore(e.expiresAt());
    }

    private Instant expiry(Instant now) {
        try {
            return now.plus(ttl);
        } catch (RuntimeException ex) {
            return null; // beyond the representable range: never expires
        }
    }

    public synchronized Optional<V> get(K key) {
        if (key == null) throw new IllegalArgumentException("null key");
        Entry<V> e = map.get(key);
        if (e == null) return Optional.empty();
        if (expired(e, clock.instant())) {
            map.remove(key);
            return Optional.empty();
        }
        map.remove(key);
        map.put(key, e);
        return Optional.of(e.value());
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) throw new IllegalArgumentException("null");
        Instant now = clock.instant();
        map.remove(key);
        map.put(key, new Entry<>(value, expiry(now)));
        if (map.size() > capacity) {
            for (Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator(); it.hasNext();) {
                if (expired(it.next().getValue(), now)) it.remove();
            }
            while (map.size() > capacity) {
                Iterator<K> it = map.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    public synchronized int size() {
        Instant now = clock.instant();
        int n = 0;
        for (Entry<V> e : map.values()) if (!expired(e, now)) n++;
        return n;
    }
}
