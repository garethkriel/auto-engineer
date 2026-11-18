package ae.crdt;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Grow-only counter: one monotonic slot per replica; merge takes the pointwise maximum. */
public final class GCounter {
    private static final GCounter EMPTY = new GCounter(Map.of());
    private final Map<String, Long> slots;

    private GCounter(Map<String, Long> slots) {
        this.slots = slots;
    }

    public static GCounter empty() {
        return EMPTY;
    }

    public GCounter increment(String replica, long n) {
        Objects.requireNonNull(replica);
        if (replica.isEmpty()) {
            throw new IllegalArgumentException("empty replica id");
        }
        if (n < 0) {
            throw new IllegalArgumentException("negative increment");
        }
        if (n == 0) {
            return this;
        }
        Map<String, Long> copy = new TreeMap<>(slots);
        copy.merge(replica, n, Math::addExact);
        return new GCounter(Map.copyOf(copy));
    }

    public long value() {
        long sum = 0;
        for (long v : slots.values()) {
            sum = Math.addExact(sum, v);
        }
        return sum;
    }

    public GCounter merge(GCounter other) {
        Map<String, Long> copy = new TreeMap<>(slots);
        other.slots.forEach((k, v) -> copy.merge(k, v, Math::max));
        return new GCounter(Map.copyOf(copy));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GCounter g && slots.equals(g.slots);
    }

    @Override
    public int hashCode() {
        return slots.hashCode();
    }

    @Override
    public String toString() {
        return "GCounter" + new TreeMap<>(slots);
    }
}
