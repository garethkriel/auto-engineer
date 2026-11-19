package ae.bitemporal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable valid-time function: sorted, disjoint segments in which adjacent segments always differ in
 * value (they are coalesced), so two timelines are equal exactly when they describe the same function.
 *
 * @param <V> value type
 */
public final class Timeline<V> {

    private static final Timeline<?> EMPTY = new Timeline<>(List.of());

    private final List<Segment<V>> segments;

    private Timeline(List<Segment<V>> segments) {
        this.segments = segments;
    }

    /** The timeline with no values. */
    @SuppressWarnings("unchecked")
    public static <V> Timeline<V> empty() {
        return (Timeline<V>) EMPTY;
    }

    /** Sorted, disjoint, coalesced segments; unmodifiable. */
    public List<Segment<V>> segments() {
        return segments;
    }

    /** The value at a valid instant, if any. */
    public Optional<V> valueAt(long validTime) {
        int lo = 0;
        int hi = segments.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Segment<V> s = segments.get(mid);
            if (validTime < s.from()) {
                hi = mid - 1;
            } else if (validTime >= s.to()) {
                lo = mid + 1;
            } else {
                return Optional.of(s.value());
            }
        }
        return Optional.empty();
    }

    /** Returns a timeline where {@code value} holds on {@code [from, to)} and everything else is unchanged. */
    public Timeline<V> assign(long from, long to, V value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        checkRange(from, to);
        List<Segment<V>> out = new ArrayList<>(segments.size() + 2);
        boolean placed = false;
        for (Segment<V> s : segments) {
            if (s.to() <= from) {
                out.add(s);
            } else if (s.from() >= to) {
                if (!placed) {
                    out.add(new Segment<>(from, to, value));
                    placed = true;
                }
                out.add(s);
            } else {
                if (s.from() < from) {
                    out.add(new Segment<>(s.from(), from, s.value()));
                }
                if (!placed) {
                    out.add(new Segment<>(from, to, value));
                    placed = true;
                }
                if (s.to() > to) {
                    out.add(new Segment<>(to, s.to(), s.value()));
                }
            }
        }
        if (!placed) {
            out.add(new Segment<>(from, to, value));
        }
        return of(coalesce(out));
    }

    /** Returns a timeline with no value on {@code [from, to)} and everything else unchanged. */
    public Timeline<V> clear(long from, long to) {
        checkRange(from, to);
        List<Segment<V>> out = new ArrayList<>(segments.size() + 1);
        for (Segment<V> s : segments) {
            if (s.to() <= from || s.from() >= to) {
                out.add(s);
                continue;
            }
            if (s.from() < from) {
                out.add(new Segment<>(s.from(), from, s.value()));
            }
            if (s.to() > to) {
                out.add(new Segment<>(to, s.to(), s.value()));
            }
        }
        return of(out);
    }

    private static void checkRange(long from, long to) {
        if (from >= to) {
            throw new IllegalArgumentException("from must be < to: [" + from + ", " + to + ")");
        }
    }

    private static <V> List<Segment<V>> coalesce(List<Segment<V>> in) {
        List<Segment<V>> out = new ArrayList<>(in.size());
        for (Segment<V> s : in) {
            int last = out.size() - 1;
            if (last >= 0 && out.get(last).to() == s.from() && out.get(last).value().equals(s.value())) {
                out.set(last, new Segment<>(out.get(last).from(), s.to(), s.value()));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static <V> Timeline<V> of(List<Segment<V>> list) {
        return list.isEmpty() ? empty() : new Timeline<>(List.copyOf(list));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Timeline<?> t && segments.equals(t.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(segments);
    }

    @Override
    public String toString() {
        return "Timeline" + segments;
    }
}
