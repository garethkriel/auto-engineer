package ae.crdt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Observed-remove set with add-wins semantics. Every add mints a unique tag; a remove
 * tombstones only the tags its replica has observed, so a concurrent add survives.
 */
public final class OrSet<E> {
    /** Unique identity of one add operation. */
    public record Tag(String replica, long counter) { }

    private record Entry<E>(E element, Tag tag) { }

    private static final OrSet<?> EMPTY = new OrSet<>(Set.of(), Set.of(), Map.of());
    private final Set<Entry<E>> entries;
    private final Set<Tag> tombstones;
    private final Map<String, Long> clock;

    private OrSet(Set<Entry<E>> entries, Set<Tag> tombstones, Map<String, Long> clock) {
        this.entries = entries;
        this.tombstones = tombstones;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public static <E> OrSet<E> empty() {
        return (OrSet<E>) EMPTY;
    }

    public OrSet<E> add(E element, String replica) {
        Objects.requireNonNull(element);
        if (replica == null || replica.isEmpty()) {
            throw new IllegalArgumentException("bad replica id");
        }
        long next = clock.getOrDefault(replica, 0L) + 1;
        Set<Entry<E>> e = new HashSet<>(entries);
        e.add(new Entry<>(element, new Tag(replica, next)));
        Map<String, Long> c = new HashMap<>(clock);
        c.put(replica, next);
        return new OrSet<>(Set.copyOf(e), tombstones, Map.copyOf(c));
    }

    /** Removes the element's currently observed tags; absent element returns this. */
    public OrSet<E> remove(E element) {
        Set<Tag> observed = entries.stream()
                .filter(en -> en.element().equals(element))
                .map(Entry::tag)
                .collect(Collectors.toSet());
        if (tombstones.containsAll(observed)) {
            return this;
        }
        Set<Tag> t = new HashSet<>(tombstones);
        t.addAll(observed);
        return new OrSet<>(entries, Set.copyOf(t), clock);
    }

    public boolean contains(E element) {
        return entries.stream().anyMatch(en -> en.element().equals(element) && !tombstones.contains(en.tag()));
    }

    public Set<E> elements() {
        return entries.stream().filter(en -> !tombstones.contains(en.tag()))
                .map(Entry::element).collect(Collectors.toUnmodifiableSet());
    }

    public OrSet<E> merge(OrSet<E> other) {
        Set<Entry<E>> e = new HashSet<>(entries);
        e.addAll(other.entries);
        Set<Tag> t = new HashSet<>(tombstones);
        t.addAll(other.tombstones);
        Map<String, Long> c = new HashMap<>(clock);
        other.clock.forEach((k, v) -> c.merge(k, v, Math::max));
        return new OrSet<>(Set.copyOf(e), Set.copyOf(t), Map.copyOf(c));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OrSet<?> s && entries.equals(s.entries) && tombstones.equals(s.tombstones)
                && clock.equals(s.clock);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entries, tombstones, clock);
    }
}
