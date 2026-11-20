package bench.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Thread-safe LRU cache whose entries expire a fixed ttl after their last put. */
public final class ExpiringCache<K, V> {
    private static final class Entry<V> {
        final V value;
        final Instant putTime;

        Entry(V value, Instant putTime) {
            this.value = value;
            this.putTime = putTime;
        }
    }

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
        try {
            return !now.isBefore(e.putTime.plus(ttl));
        } catch (ArithmeticException | java.time.DateTimeException ex) {
            return false; // expiry beyond representable range: never expires
        }
    }

    public synchronized Optional<V> get(K key) {
        if (key == null) throw new IllegalArgumentException("key");
        Entry<V> e = map.get(key);
        if (e == null) return Optional.empty();
        if (expired(e, clock.instant())) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(e.value);
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) throw new IllegalArgumentException("null key or value");
        Instant now = clock.instant();
        map.remove(key);
        map.put(key, new Entry<>(value, now));
        if (map.size() > capacity) {
            Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                if (expired(it.next().getValue(), now)) it.remove();
            }
            while (map.size() > capacity) {
                Iterator<K> ki = map.keySet().iterator();
                ki.next();
                ki.remove();
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
