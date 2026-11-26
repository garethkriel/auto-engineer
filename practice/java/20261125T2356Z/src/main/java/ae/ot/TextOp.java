package ae.ot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An immutable text operation: a sequence of retain / insert / delete components
 * measured in UTF-16 chars. Instances are always normalised (see {@link Builder}).
 */
public final class TextOp {

    /** One component of an operation. */
    public sealed interface Comp permits Retain, Insert, Delete {}

    /** Keep {@code n} chars. */
    public record Retain(int n) implements Comp {}

    /** Insert {@code text}. */
    public record Insert(String text) implements Comp {}

    /** Delete {@code n} chars. */
    public record Delete(int n) implements Comp {}

    /** Result of {@link #transform}. */
    public record Pair(TextOp first, TextOp second) {}

    private final List<Comp> comps;
    private final int baseLength;
    private final int targetLength;

    private TextOp(List<Comp> comps) {
        this.comps = List.copyOf(comps);
        int b = 0;
        int t = 0;
        for (Comp c : comps) {
            switch (c) {
                case Retain r -> { b += r.n(); t += r.n(); }
                case Insert i -> t += i.text().length();
                case Delete d -> b += d.n();
            }
        }
        baseLength = b;
        targetLength = t;
    }

    /** Builds a normalised operation. */
    public static final class Builder {
        private final List<Comp> list = new ArrayList<>();

        public Builder retain(int n) {
            if (n < 0) throw new IllegalArgumentException("negative retain");
            if (n == 0) return this;
            int last = list.size() - 1;
            if (last >= 0 && list.get(last) instanceof Retain r) list.set(last, new Retain(r.n() + n));
            else list.add(new Retain(n));
            return this;
        }

        public Builder delete(int n) {
            if (n < 0) throw new IllegalArgumentException("negative delete");
            if (n == 0) return this;
            int last = list.size() - 1;
            if (last >= 0 && list.get(last) instanceof Delete d) list.set(last, new Delete(d.n() + n));
            else list.add(new Delete(n));
            return this;
        }

        public Builder insert(String s) {
            Objects.requireNonNull(s, "text");
            if (s.isEmpty()) return this;
            int last = list.size() - 1;
            if (last >= 0 && list.get(last) instanceof Delete) {
                // canonical order: insert before an adjacent delete
                if (last >= 1 && list.get(last - 1) instanceof Insert i) list.set(last - 1, new Insert(i.text() + s));
                else list.add(last, new Insert(s));
            } else if (last >= 0 && list.get(last) instanceof Insert i) {
                list.set(last, new Insert(i.text() + s));
            } else {
                list.add(new Insert(s));
            }
            return this;
        }

        public TextOp build() {
            return new TextOp(list);
        }
    }

    public List<Comp> components() { return comps; }

    public int baseLength() { return baseLength; }

    public int targetLength() { return targetLength; }

    /** Applies this op to {@code doc}; the doc length must equal {@link #baseLength()}. */
    public String apply(String doc) {
        if (doc.length() != baseLength) {
            throw new IllegalArgumentException("base length " + baseLength + " != document length " + doc.length());
        }
        StringBuilder sb = new StringBuilder(targetLength);
        int pos = 0;
        for (Comp c : comps) {
            switch (c) {
                case Retain r -> { sb.append(doc, pos, pos + r.n()); pos += r.n(); }
                case Insert i -> sb.append(i.text());
                case Delete d -> pos += d.n();
            }
        }
        return sb.toString();
    }

    /** The op that undoes this one when applied to the result of {@code apply(doc)}. */
    public TextOp invert(String doc) {
        if (doc.length() != baseLength) {
            throw new IllegalArgumentException("base length " + baseLength + " != document length " + doc.length());
        }
        Builder b = new Builder();
        int pos = 0;
        for (Comp c : comps) {
            switch (c) {
                case Retain r -> { b.retain(r.n()); pos += r.n(); }
                case Insert i -> b.delete(i.text().length());
                case Delete d -> { b.insert(doc.substring(pos, pos + d.n())); pos += d.n(); }
            }
        }
        return b.build();
    }

    /** Cursor over components allowing partial consumption. */
    private static final class Cur {
        private final List<Comp> l;
        private int i;
        private int off;

        Cur(TextOp op) { l = op.comps; }

        Comp peek() { return i < l.size() ? l.get(i) : null; }

        int rem() {
            return switch (l.get(i)) {
                case Retain r -> r.n() - off;
                case Insert s -> s.text().length() - off;
                case Delete d -> d.n() - off;
            };
        }

        String text(int n) { return ((Insert) l.get(i)).text().substring(off, off + n); }

        void skip(int n) {
            if (n == rem()) { i++; off = 0; } else { off += n; }
        }
    }

    /** Composition: applying the result equals applying {@code a} then {@code b}. */
    public static TextOp compose(TextOp a, TextOp b) {
        if (a.targetLength != b.baseLength) {
            throw new IllegalArgumentException("compose length mismatch: " + a.targetLength + " vs " + b.baseLength);
        }
        Builder out = new Builder();
        Cur x = new Cur(a);
        Cur y = new Cur(b);
        while (x.peek() != null || y.peek() != null) {
            if (x.peek() instanceof Delete) {
                int n = x.rem();
                out.delete(n);
                x.skip(n);
            } else if (y.peek() instanceof Insert) {
                int n = y.rem();
                out.insert(y.text(n));
                y.skip(n);
            } else {
                int n = Math.min(x.rem(), y.rem());
                Comp ca = x.peek();
                Comp cb = y.peek();
                if (ca instanceof Retain && cb instanceof Retain) out.retain(n);
                else if (ca instanceof Retain) out.delete(n);
                else if (cb instanceof Retain) out.insert(x.text(n));
                // insert then delete cancels
                x.skip(n);
                y.skip(n);
            }
        }
        return out.build();
    }

    /**
     * Transforms two concurrent ops with the same base. Returns {@code (a', b')} such that
     * {@code b'.apply(a.apply(d)) == a'.apply(b.apply(d))}. When both insert at the same
     * position, {@code a}'s text comes first.
     */
    public static Pair transform(TextOp a, TextOp b) {
        if (a.baseLength != b.baseLength) {
            throw new IllegalArgumentException("transform base mismatch: " + a.baseLength + " vs " + b.baseLength);
        }
        Builder ap = new Builder();
        Builder bp = new Builder();
        Cur x = new Cur(a);
        Cur y = new Cur(b);
        while (x.peek() != null || y.peek() != null) {
            if (x.peek() instanceof Insert) {
                int n = x.rem();
                ap.insert(x.text(n));
                bp.retain(n);
                x.skip(n);
            } else if (y.peek() instanceof Insert) {
                int n = y.rem();
                bp.insert(y.text(n));
                ap.retain(n);
                y.skip(n);
            } else {
                int n = Math.min(x.rem(), y.rem());
                Comp ca = x.peek();
                Comp cb = y.peek();
                if (ca instanceof Retain && cb instanceof Retain) { ap.retain(n); bp.retain(n); }
                else if (ca instanceof Delete && cb instanceof Retain) ap.delete(n);
                else if (ca instanceof Retain && cb instanceof Delete) bp.delete(n);
                // both delete: already gone on the other side
                x.skip(n);
                y.skip(n);
            }
        }
        return new Pair(ap.build(), bp.build());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TextOp t && comps.equals(t.comps);
    }

    @Override
    public int hashCode() { return comps.hashCode(); }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        for (Comp c : comps) {
            switch (c) {
                case Retain r -> parts.add("R" + r.n());
                case Insert i -> parts.add("I\"" + i.text() + "\"");
                case Delete d -> parts.add("D" + d.n());
            }
        }
        return "[" + String.join(", ", parts) + "]";
    }
}
