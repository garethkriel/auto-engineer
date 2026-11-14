package pc;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Immutable FIFO queue built from two persistent stacks (amortised O(1) operations). */
public final class PersistentQueue<T> implements Iterable<T> {
    private static final PersistentQueue<?> EMPTY =
            new PersistentQueue<>(PersistentStack.empty(), PersistentStack.empty());

    /** Result of {@link #dequeue()}. */
    public record Dequeued<T>(T head, PersistentQueue<T> rest) {}

    private final PersistentStack<T> front; // oldest on top
    private final PersistentStack<T> back; // newest on top

    private PersistentQueue(PersistentStack<T> front, PersistentStack<T> back) {
        this.front = front;
        this.back = back;
    }

    @SuppressWarnings("unchecked")
    public static <T> PersistentQueue<T> empty() {
        return (PersistentQueue<T>) EMPTY;
    }

    public PersistentQueue<T> enqueue(T value) {
        Objects.requireNonNull(value, "value");
        return new PersistentQueue<>(front, back.push(value));
    }

    public Dequeued<T> dequeue() {
        if (isEmpty()) throw new IllegalStateException("empty queue");
        PersistentStack<T> f = front;
        PersistentStack<T> b = back;
        if (f.isEmpty()) { // reverse back into front
            for (T t : b) f = f.push(t);
            b = PersistentStack.empty();
        }
        return new Dequeued<>(f.peek().orElseThrow(), new PersistentQueue<>(f.pop(), b));
    }

    public Optional<T> peek() {
        return isEmpty() ? Optional.empty() : Optional.of(dequeue().head());
    }

    public int size() {
        return front.size() + back.size();
    }

    public boolean isEmpty() {
        return front.isEmpty() && back.isEmpty();
    }

    @Override
    public Iterator<T> iterator() {
        PersistentStack<T> reversedBack = PersistentStack.empty();
        for (T t : back) reversedBack = reversedBack.push(t);
        Iterator<T> fi = front.iterator();
        Iterator<T> bi = reversedBack.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return fi.hasNext() || bi.hasNext();
            }

            @Override
            public T next() {
                if (fi.hasNext()) return fi.next();
                if (bi.hasNext()) return bi.next();
                throw new NoSuchElementException();
            }
        };
    }

    public Stream<T> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersistentQueue<?> other) || other.size() != size()) return false;
        Iterator<T> a = iterator();
        Iterator<?> b = other.iterator();
        while (a.hasNext()) {
            if (!a.next().equals(b.next())) return false;
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
