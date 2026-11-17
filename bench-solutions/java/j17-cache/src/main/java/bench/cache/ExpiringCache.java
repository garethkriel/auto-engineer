package bench.cache;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** LRU cache whose entries expire ttl after they were last put. */
public final class ExpiringCache<K, V> {
    private record Entry<V>(V value, Instant expiresAt) {} // expiresAt null = never

    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<K, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);

    public ExpiringCache(int capacity, Duration ttl, Clock clock) {
        if (capacity < 1 || ttl == null || ttl.isZero() || ttl.isNegative() || clock == null) {
            throw new IllegalArgumentException("bad arguments");
        }
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = clock;
    }

    private static boolean expired(Entry<?> e, Instant now) {
        return e.expiresAt() != null && !now.isBefore(e.expiresAt());
    }

    private void purge(Instant now) {
        for (Iterator<Entry<V>> it = map.values().iterator(); it.hasNext();) {
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
        return Optional.of(e.value());
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("null key or value");
        }
        Instant now = clock.instant();
        Instant exp;
        try {
            exp = now.plus(ttl);
        } catch (DateTimeException | ArithmeticException ex) {
            exp = null;
        }
        map.put(key, new Entry<>(value, exp));
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
