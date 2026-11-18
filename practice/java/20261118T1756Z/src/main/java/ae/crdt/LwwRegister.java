package ae.crdt;

import java.util.Objects;
import java.util.Optional;

/** Last-writer-wins register ordered by (time, replica id, string form of the value). */
public final class LwwRegister<T> {
    private static final LwwRegister<?> EMPTY = new LwwRegister<>(null, 0, "", false);
    private final T value;
    private final long time;
    private final String replica;
    private final boolean present;

    private LwwRegister(T value, long time, String replica, boolean present) {
        this.value = value;
        this.time = time;
        this.replica = replica;
        this.present = present;
    }

    @SuppressWarnings("unchecked")
    public static <T> LwwRegister<T> empty() {
        return (LwwRegister<T>) EMPTY;
    }

    /** Writes value with the given stamp; an older stamp than the current one loses. */
    public LwwRegister<T> set(T newValue, long newTime, String newReplica) {
        Objects.requireNonNull(newValue);
        Objects.requireNonNull(newReplica);
        return merge(new LwwRegister<>(newValue, newTime, newReplica, true));
    }

    public Optional<T> get() {
        return present ? Optional.of(value) : Optional.empty();
    }

    public LwwRegister<T> merge(LwwRegister<T> other) {
        if (!present) {
            return other;
        }
        if (!other.present) {
            return this;
        }
        return compare(other) >= 0 ? this : other;
    }

    private int compare(LwwRegister<T> o) {
        int c = Long.compare(time, o.time);
        if (c == 0) {
            c = replica.compareTo(o.replica);
        }
        if (c == 0) {
            c = String.valueOf(value).compareTo(String.valueOf(o.value));
        }
        return c;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LwwRegister<?> r)) {
            return false;
        }
        if (!present || !r.present) {
            return present == r.present;
        }
        return time == r.time && replica.equals(r.replica) && value.equals(r.value);
    }

    @Override
    public int hashCode() {
        return present ? Objects.hash(value, time, replica) : 0;
    }
}
