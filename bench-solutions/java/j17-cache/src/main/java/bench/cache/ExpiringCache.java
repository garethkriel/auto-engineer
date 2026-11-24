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
        final Instant expiresAt;

        Entry(V value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
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

    private static boolean expired(Entry<?> e, Instant now) {
        return !now.isBefore(e.expiresAt);
    }

    private void purge(Instant now) {
        Iterator<Entry<V>> it = map.values().iterator();
        while (it.hasNext()) {
            if (expired(it.next(), now)) {
                it.remove();
            }
        }
    }

    public synchronized Optional<V> get(K key) {
        Instant now = clock.instant();
        Entry<V> e = map.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (expired(e, now)) {
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
        Instant expiresAt;
        try {
            expiresAt = now.plus(ttl);
        } catch (ArithmeticException | java.time.DateTimeException ex) {
            expiresAt = Instant.MAX;
        }
        map.remove(key);
        map.put(key, new Entry<>(value, expiresAt));
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
}
