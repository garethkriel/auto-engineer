package pc;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Immutable LIFO stack; every version shares its tail with older versions. */
public final class PersistentStack<T> implements Iterable<T> {
    private static final PersistentStack<?> EMPTY = new PersistentStack<>(null, null, 0);

    private final T head;
    private final PersistentStack<T> tail;
    private final int size;

    private PersistentStack(T head, PersistentStack<T> tail, int size) {
        this.head = head;
        this.tail = tail;
        this.size = size;
    }

    @SuppressWarnings("unchecked")
    public static <T> PersistentStack<T> empty() {
        return (PersistentStack<T>) EMPTY;
    }

    public PersistentStack<T> push(T value) {
        Objects.requireNonNull(value, "value");
        return new PersistentStack<>(value, this, Math.addExact(size, 1));
    }

    public PersistentStack<T> pop() {
        if (size == 0) throw new IllegalStateException("empty stack");
        return tail;
    }

    public Optional<T> peek() {
        return Optional.ofNullable(head);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private PersistentStack<T> cur = PersistentStack.this;

            @Override
            public boolean hasNext() {
                return cur.size > 0;
            }

            @Override
            public T next() {
                if (cur.size == 0) throw new NoSuchElementException();
                T v = cur.head;
                cur = cur.tail;
                return v;
            }
        };
    }

    public Stream<T> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersistentStack<?> other) || other.size != size) return false;
        PersistentStack<?> a = this;
        PersistentStack<?> b = other;
        while (a.size > 0) {
            if (a == b) return true; // shared tail
            if (!a.head.equals(b.head)) return false;
            a = a.tail;
            b = b.tail;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (T t : this) h = 31 * h + t.hashCode();
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (T t : this) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(t);
        }
        return sb.append(']').toString();
    }
}
