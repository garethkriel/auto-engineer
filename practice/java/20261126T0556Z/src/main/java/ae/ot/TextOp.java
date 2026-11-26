package ae.ot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable operation on a text document: a sequence of {@link Retain}, {@link Insert} and
 * {@link Delete} components that rewrites a document of exactly {@link #baseLength()} UTF-16 code
 * units into one of {@link #targetLength()} code units.
 *
 * <p>Instances are canonical: build them with {@link #builder()}, which drops empty components,
 * merges neighbours of the same kind and stores an insert before an adjacent delete, so two
 * operations with the same effect on text built in different ways are {@code equals}.
 */
public final class TextOp {

    /** One step of an operation. */
    public sealed interface Component permits Retain, Insert, Delete { }

    /** Keep the next {@code n} characters. */
    public record Retain(int n) implements Component {
        public Retain {
            if (n <= 0) throw new IllegalArgumentException("retain must be positive");
        }
    }

    /** Insert {@code text} at the current position. */
    public record Insert(String text) implements Component {
        public Insert {
            Objects.requireNonNull(text);
            if (text.isEmpty()) throw new IllegalArgumentException("empty insert");
        }
    }

    /** Remove the next {@code n} characters. */
    public record Delete(int n) implements Component {
        public Delete {
            if (n <= 0) throw new IllegalArgumentException("delete must be positive");
        }
    }

    /** The result of {@link #transform}: each operation rewritten to follow the other. */
    public record Pair(TextOp left, TextOp right) { }

    private final List<Component> parts;
    private final int baseLength;
    private final int targetLength;

    private TextOp(List<Component> parts) {
        this.parts = Collections.unmodifiableList(parts);
        int base = 0;
        int target = 0;
        for (Component c : parts) {
            switch (c) {
                case Retain r -> { base += r.n(); target += r.n(); }
                case Insert i -> target += i.text().length();
                case Delete d -> base += d.n();
            }
        }
        this.baseLength = base;
        this.targetLength = target;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The operation that keeps a document of {@code length} characters unchanged. */
    public static TextOp identity(int length) {
        return builder().retain(length).build();
    }

    /** Collects components and normalises them. */
    public static final class Builder {
        private final ArrayList<Component> parts = new ArrayList<>();

        public Builder retain(int n) {
            if (n < 0) throw new IllegalArgumentException("negative retain");
            if (n == 0) return this;
            int last = parts.size() - 1;
            if (last >= 0 && parts.get(last) instanceof Retain r) parts.set(last, new Retain(r.n() + n));
            else parts.add(new Retain(n));
            return this;
        }

        public Builder insert(String text) {
            Objects.requireNonNull(text);
            if (text.isEmpty()) return this;
            int last = parts.size() - 1;
            if (last >= 0 && parts.get(last) instanceof Insert i) {
                parts.set(last, new Insert(i.text() + text));
            } else if (last >= 0 && parts.get(last) instanceof Delete) {
                if (last >= 1 && parts.get(last - 1) instanceof Insert i) {
                    parts.set(last - 1, new Insert(i.text() + text));
                } else {
                    parts.add(last, new Insert(text));
                }
            } else {
                parts.add(new Insert(text));
            }
            return this;
        }

        public Builder delete(int n) {
            if (n < 0) throw new IllegalArgumentException("negative delete");
            if (n == 0) return this;
            int last = parts.size() - 1;
            if (last >= 0 && parts.get(last) instanceof Delete d) parts.set(last, new Delete(d.n() + n));
            else parts.add(new Delete(n));
            return this;
        }

        public TextOp build() {
            return new TextOp(new ArrayList<>(parts));
        }
    }

    public List<Component> components() {
        return parts;
    }

    /** Length of the documents this operation applies to. */
    public int baseLength() {
        return baseLength;
    }

    /** Length of the document it produces. */
    public int targetLength() {
        return targetLength;
    }

    /** True when the operation leaves every document unchanged. */
    public boolean isNoop() {
        for (Component c : parts) if (!(c instanceof Retain)) return false;
        return true;
    }

    /** Applies this operation to {@code doc}, whose length must equal {@link #baseLength()}. */
    public String apply(String doc) {
        Objects.requireNonNull(doc);
        if (doc.length() != baseLength) {
            throw new IllegalArgumentException("document length " + doc.length() + " != base length " + baseLength);
        }
        StringBuilder out = new StringBuilder(targetLength);
        int pos = 0;
        for (Component c : parts) {
            switch (c) {
                case Retain r -> { out.append(doc, pos, pos + r.n()); pos += r.n(); }
                case Insert i -> out.append(i.text());
                case Delete d -> pos += d.n();
            }
        }
        return out.toString();
    }

    /**
     * The operation undoing this one on {@code docBefore}, the document this operation was applied to.
     */
    public TextOp invert(String docBefore) {
        Objects.requireNonNull(docBefore);
        if (docBefore.length() != baseLength) {
            throw new IllegalArgumentException("document length " + docBefore.length() + " != base length " + baseLength);
        }
        Builder b = builder();
        int pos = 0;
        for (Component c : parts) {
            switch (c) {
                case Retain r -> { b.retain(r.n()); pos += r.n(); }
                case Insert i -> b.delete(i.text().length());
                case Delete d -> { b.insert(docBefore.substring(pos, pos + d.n())); pos += d.n(); }
            }
        }
        return b.build();
    }

    /** Walks a component list while splitting components at arbitrary lengths. */
    private static final class Cursor {
        private final List<Component> parts;
        private int index;
        private int offset;   // chars already consumed from the current component

        Cursor(List<Component> parts) {
            this.parts = parts;
        }

        Component current() {
            return index < parts.size() ? parts.get(index) : null;
        }

        /** Remaining length of the current component. */
        int remaining() {
            return switch (current()) {
                case Retain r -> r.n() - offset;
                case Insert i -> i.text().length() - offset;
                case Delete d -> d.n() - offset;
            };
        }

        /** Remaining text of the current insert, first {@code k} chars. */
        String text(int k) {
            return ((Insert) current()).text().substring(offset, offset + k);
        }

        /** Consumes {@code k} chars of the current component, moving on when it is used up. */
        void take(int k) {
            if (k <= 0 || k > remaining()) throw new IllegalStateException("bad take " + k);
            offset += k;
            if (remaining() == 0) {
                index++;
                offset = 0;
            }
        }
    }

    /**
     * The operation equivalent to applying this one and then {@code next}.
     *
     * @throws IllegalArgumentException if {@code next} does not start from this operation's result length
     */
    public TextOp compose(TextOp next) {
        Objects.requireNonNull(next);
        if (targetLength != next.baseLength) {
            throw new IllegalArgumentException("compose length mismatch: " + targetLength + " vs " + next.baseLength);
        }
        Builder out = builder();
        Cursor c1 = new Cursor(parts);
        Cursor c2 = new Cursor(next.parts);
        while (true) {
            Component a = c1.current();
            Component b = c2.current();
            if (a instanceof Delete) {
                int n = c1.remaining();
                out.delete(n);
                c1.take(n);
                continue;
            }
            if (b instanceof Insert) {
                int n = c2.remaining();
                out.insert(c2.text(n));
                c2.take(n);
                continue;
            }
            if (a == null && b == null) break;
            if (a == null || b == null) throw new IllegalStateException("unreachable: lengths were checked");
            int m = Math.min(c1.remaining(), c2.remaining());
            if (a instanceof Retain && b instanceof Retain) {
                out.retain(m);
            } else if (a instanceof Insert && b instanceof Retain) {
                out.insert(c1.text(m));
            } else if (a instanceof Retain && b instanceof Delete) {
                out.delete(m);
            }
            // insert followed by delete cancels
            c1.take(m);
            c2.take(m);
        }
        return out.build();
    }

    /**
     * Rewrites two operations made concurrently on the same document so each can follow the other.
     * {@code left} is {@code a} adapted to apply after {@code b}; {@code right} is {@code b} adapted
     * to apply after {@code a}. When both insert at the same position, {@code a}'s text comes first.
     */
    public static Pair transform(TextOp a, TextOp b) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        if (a.baseLength != b.baseLength) {
            throw new IllegalArgumentException("transform base mismatch: " + a.baseLength + " vs " + b.baseLength);
        }
        Builder left = builder();
        Builder right = builder();
        Cursor ca = new Cursor(a.parts);
        Cursor cb = new Cursor(b.parts);
        while (true) {
            Component x = ca.current();
            Component y = cb.current();
            if (x instanceof Insert) {
                int n = ca.remaining();
                left.insert(ca.text(n));
                right.retain(n);
                ca.take(n);
                continue;
            }
            if (y instanceof Insert) {
                int n = cb.remaining();
                left.retain(n);
                right.insert(cb.text(n));
                cb.take(n);
                continue;
            }
            if (x == null && y == null) break;
            if (x == null || y == null) throw new IllegalStateException("unreachable: lengths were checked");
            int m = Math.min(ca.remaining(), cb.remaining());
            if (x instanceof Retain && y instanceof Retain) {
                left.retain(m);
                right.retain(m);
            } else if (x instanceof Delete && y instanceof Retain) {
                left.delete(m);
            } else if (x instanceof Retain && y instanceof Delete) {
                right.delete(m);
            }
            ca.take(m);
            cb.take(m);
        }
        return new Pair(left.build(), right.build());
    }

    /**
     * Maps a cursor position (a gap between characters, {@code 0..baseLength}) through this operation.
     * A cursor inside a deleted range collapses to its start. A cursor exactly at an insert moves
     * past the inserted text when {@code advanceOverInsert} is true, otherwise stays before it.
     */
    public int transformIndex(int index, boolean advanceOverInsert) {
        if (index < 0 || index > baseLength) {
            throw new IllegalArgumentException("index " + index + " outside 0.." + baseLength);
        }
        int base = 0;
        int out = 0;
        for (Component c : parts) {
            switch (c) {
                case Insert i -> {
                    if (base == index && !advanceOverInsert) return out;
                    out += i.text().length();
                }
                case Retain r -> {
                    if (index < base + r.n()) return out + (index - base);
                    base += r.n();
                    out += r.n();
                }
                case Delete d -> {
                    if (index < base + d.n()) return out;
                    base += d.n();
                }
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TextOp t && parts.equals(t.parts);
    }

    @Override
    public int hashCode() {
        return parts.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            switch (parts.get(i)) {
                case Retain r -> sb.append('R').append(r.n());
                case Insert ins -> sb.append("I\"").append(ins.text()).append('"');
                case Delete d -> sb.append('D').append(d.n());
            }
        }
        return sb.append(']').toString();
    }
}
