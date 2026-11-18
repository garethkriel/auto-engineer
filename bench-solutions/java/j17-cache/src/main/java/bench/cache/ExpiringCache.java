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
    private static final class Entry<V> {
        final V value;
        final Instant expiry; // null means never expires (overflow)

        Entry(V value, Instant expiry) {
            this.value = value;
            this.expiry = expiry;
        }

        boolean expired(Instant now) {
            return expiry != null && !now.isBefore(expiry);
        }
    }

    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<K, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);

    public ExpiringCache(int capacity, Duration ttl, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity < 1");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("bad ttl");
        }
        if (clock == null) {
            throw new IllegalArgumentException("null clock");
        }
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized Optional<V> get(K key) {
        if (key == null) {
            throw new IllegalArgumentException("null key");
        }
        Instant now = clock.instant();
        Entry<V> e = map.get(key); // marks as recently used
        if (e == null) {
            return Optional.empty();
        }
        if (e.expired(now)) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(e.value);
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("null key or value");
        }
        Instant now = clock.instant();
        Instant expiry;
        try {
            expiry = now.plus(ttl);
        } catch (RuntimeException ex) {
            expiry = null;
        }
        map.put(key, new Entry<>(value, expiry));
        if (map.size() > capacity) {
            purge(now);
            while (map.size() > capacity) {
                Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    public synchronized int size() {
        purge(clock.instant());
        return map.size();
    }

    private void purge(Instant now) {
        map.values().removeIf(e -> e.expired(now));
    }
}
