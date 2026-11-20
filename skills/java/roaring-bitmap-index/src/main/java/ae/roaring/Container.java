package ae.roaring;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.IntConsumer;

/**
 * The 65536-value chunk behind one 16-bit key. Three encodings:
 * {@link Array} (card &lt;= 4096), {@link Bitmap} (card &gt; 4096) and {@link Run} (only via optimisation or range ops).
 * Operations that change the content return the container to keep using (possibly a different kind) or {@code null}
 * when it became empty; callers must replace their reference.
 */
abstract class Container {
    static final int ARRAY_MAX = 4096;
    static final int WORDS = 1024;
    static final int MAX = 0xFFFF;

    abstract String kind();

    abstract int cardinality();

    abstract boolean contains(int v);

    abstract Container add(int v);

    abstract Container remove(int v);

    /** ORs this container's bits into {@code w}. */
    abstract void fill(long[] w);

    abstract void forEach(IntConsumer c);

    abstract Container copy();

    abstract int sizeBytes();

    /** Number of values &lt;= v. */
    abstract int rank(int v);

    /** The i-th smallest value, 0-based. */
    abstract int select(int i);

    final int first() {
        return select(0);
    }

    final int last() {
        return select(cardinality() - 1);
    }

    final long[] words() {
        long[] w = new long[WORDS];
        fill(w);
        return w;
    }

    // ---------------- factories ----------------

    /** Canonical array-or-bitmap container for {@code w}, or null when empty. Takes ownership of {@code w}. */
    static Container fromWords(long[] w) {
        int card = 0;
        for (long x : w) card += Long.bitCount(x);
        if (card == 0) return null;
        if (card > ARRAY_MAX) return new Bitmap(w, card);
        char[] a = new char[card];
        int n = 0;
        for (int i = 0; i < WORDS; i++) {
            long x = w[i];
            while (x != 0) {
                a[n++] = (char) ((i << 6) + Long.numberOfTrailingZeros(x));
                x &= x - 1;
            }
        }
        return new Array(a);
    }

    static Container runOf(int lo, int hi) {
        return new Run(new char[] {(char) lo, (char) (hi - lo)}, hi - lo + 1);
    }

    /** Array/bitmap form of c (never a run); a fresh copy when c is already plain. */
    static Container plain(Container c) {
        return c instanceof Run ? fromWords(c.words()) : c.copy();
    }

    /** Picks the smallest encoding; ties favour array/bitmap. */
    static Container optimize(Container c) {
        int card = c.cardinality();
        int base = card <= ARRAY_MAX ? 2 * card : 8192;
        if (c instanceof Run r) {
            return 2 + 4 * (r.d.length / 2) < base ? c : fromWords(c.words());
        }
        long[] w = c.words();
        int runs = 0;
        long carry = 0;
        for (long x : w) {
            runs += Long.bitCount(x & ~((x << 1) | carry));
            carry = x >>> 63;
        }
        if (2 + 4 * runs < base) return Run.fromWords(w, card, runs);
        return c;
    }

    static void applyRange(long[] w, int lo, int hi, char mode) {
        for (int wi = lo >> 6; wi <= hi >> 6; wi++) {
            long mask = -1L;
            if (wi == lo >> 6) mask &= -1L << (lo & 63);
            if (wi == hi >> 6) mask &= -1L >>> (63 - (hi & 63));
            if (mode == '+') w[wi] |= mask;
            else if (mode == '-') w[wi] &= ~mask;
            else w[wi] ^= mask;
        }
    }

    // ---------------- binary operations ----------------

    static Container and(Container a, Container b) {
        if (a instanceof Array x && b instanceof Array y) return intersect(x.a, y.a);
        if (a instanceof Array x) return filter(x, b, true);
        if (b instanceof Array y) return filter(y, a, true);
        long[] w = a.words(), v = b.words();
        for (int i = 0; i < WORDS; i++) w[i] &= v[i];
        return fromWords(w);
    }

    static Container andNot(Container a, Container b) {
        if (a instanceof Array x) return filter(x, b, false);
        long[] w = a.words(), v = b.words();
        for (int i = 0; i < WORDS; i++) w[i] &= ~v[i];
        return fromWords(w);
    }

    static Container or(Container a, Container b) {
        long[] w = a.words();
        b.fill(w);
        return fromWords(w);
    }

    static Container xor(Container a, Container b) {
        long[] w = a.words(), v = b.words();
        for (int i = 0; i < WORDS; i++) w[i] ^= v[i];
        return fromWords(w);
    }

    private static Container intersect(char[] x, char[] y) {
        char[] out = new char[Math.min(x.length, y.length)];
        int i = 0, j = 0, n = 0;
        while (i < x.length && j < y.length) {
            if (x[i] < y[j]) i++;
            else if (x[i] > y[j]) j++;
            else { out[n++] = x[i]; i++; j++; }
        }
        return n == 0 ? null : new Array(Arrays.copyOf(out, n));
    }

    /** Keeps the values of {@code x} whose membership in {@code other} equals {@code keepIfIn}. */
    private static Container filter(Array x, Container other, boolean keepIfIn) {
        char[] out = new char[x.a.length];
        int n = 0;
        for (char v : x.a) {
            if (other.contains(v) == keepIfIn) out[n++] = v;
        }
        return n == 0 ? null : new Array(Arrays.copyOf(out, n));
    }

    static boolean sameContent(Container a, Container b) {
        if (a.cardinality() != b.cardinality()) return false;
        if (a instanceof Array x && b instanceof Array y) return Arrays.equals(x.a, y.a);
        return Arrays.equals(a.words(), b.words());
    }

    /** Throws IllegalStateException when the container violates its own invariants. */
    abstract void check();

    // ================= Array =================

    static final class Array extends Container {
        final char[] a;

        Array(char[] a) {
            this.a = a;
        }

        @Override String kind() { return "array"; }
        @Override int cardinality() { return a.length; }
        @Override boolean contains(int v) { return Arrays.binarySearch(a, (char) v) >= 0; }

        @Override
        Container add(int v) {
            int i = Arrays.binarySearch(a, (char) v);
            if (i >= 0) return this;
            if (a.length == ARRAY_MAX) {
                long[] w = words();
                w[v >> 6] |= 1L << (v & 63);
                return new Bitmap(w, ARRAY_MAX + 1);
            }
            int at = -i - 1;
            char[] b = new char[a.length + 1];
            System.arraycopy(a, 0, b, 0, at);
            b[at] = (char) v;
            System.arraycopy(a, at, b, at + 1, a.length - at);
            return new Array(b);
        }

        @Override
        Container remove(int v) {
            int i = Arrays.binarySearch(a, (char) v);
            if (i < 0) return this;
            if (a.length == 1) return null;
            char[] b = new char[a.length - 1];
            System.arraycopy(a, 0, b, 0, i);
            System.arraycopy(a, i + 1, b, i, a.length - i - 1);
            return new Array(b);
        }

        @Override void fill(long[] w) { for (char v : a) w[v >> 6] |= 1L << (v & 63); }
        @Override void forEach(IntConsumer c) { for (char v : a) c.accept(v); }
        @Override Container copy() { return new Array(a.clone()); }
        @Override int sizeBytes() { return 2 * a.length; }

        @Override
        int rank(int v) {
            int i = Arrays.binarySearch(a, (char) v);
            return i >= 0 ? i + 1 : -i - 1;
        }

        @Override int select(int i) { return a[i]; }

        @Override
        void check() {
            if (a.length == 0 || a.length > ARRAY_MAX) throw new IllegalStateException("array size " + a.length);
            for (int i = 1; i < a.length; i++) {
                if (a[i - 1] >= a[i]) throw new IllegalStateException("array not strictly increasing at " + i);
            }
        }
    }

    // ================= Bitmap =================

    static final class Bitmap extends Container {
        final long[] w;
        int card;

        Bitmap(long[] w, int card) {
            this.w = w;
            this.card = card;
        }

        @Override String kind() { return "bitmap"; }
        @Override int cardinality() { return card; }
        @Override boolean contains(int v) { return (w[v >> 6] & (1L << (v & 63))) != 0; }

        @Override
        Container add(int v) {
            long bit = 1L << (v & 63);
            if ((w[v >> 6] & bit) == 0) {
                w[v >> 6] |= bit;
                card++;
            }
            return this;
        }

        @Override
        Container remove(int v) {
            long bit = 1L << (v & 63);
            if ((w[v >> 6] & bit) == 0) return this;
            w[v >> 6] &= ~bit;
            card--;
            return card > ARRAY_MAX ? this : fromWords(w);
        }

        @Override void fill(long[] out) { for (int i = 0; i < WORDS; i++) out[i] |= w[i]; }

        @Override
        void forEach(IntConsumer c) {
            for (int i = 0; i < WORDS; i++) {
                long x = w[i];
                while (x != 0) {
                    c.accept((i << 6) + Long.numberOfTrailingZeros(x));
                    x &= x - 1;
                }
            }
        }

        @Override Container copy() { return new Bitmap(w.clone(), card); }
        @Override int sizeBytes() { return 8192; }

        @Override
        int rank(int v) {
            int n = 0;
            for (int i = 0; i < (v >> 6); i++) n += Long.bitCount(w[i]);
            long mask = -1L >>> (63 - (v & 63));
            return n + Long.bitCount(w[v >> 6] & mask);
        }

        @Override
        int select(int i) {
            int left = i;
            for (int wi = 0; wi < WORDS; wi++) {
                int c = Long.bitCount(w[wi]);
                if (left < c) {
                    long x = w[wi];
                    for (int k = 0; k < left; k++) x &= x - 1;
                    return (wi << 6) + Long.numberOfTrailingZeros(x);
                }
                left -= c;
            }
            throw new IndexOutOfBoundsException("select " + i);
        }

        @Override
        void check() {
            int c = 0;
            for (long x : w) c += Long.bitCount(x);
            if (c != card) throw new IllegalStateException("bitmap card " + card + " but " + c + " bits");
            if (c <= ARRAY_MAX) throw new IllegalStateException("bitmap with only " + c + " values");
        }
    }

    // ================= Run =================

    static final class Run extends Container {
        /** (start, length-1) pairs, sorted, non-overlapping, non-adjacent. */
        final char[] d;
        final int card;

        Run(char[] d, int card) {
            this.d = d;
            this.card = card;
        }

        static Run fromWords(long[] w, int card, int runs) {
            BitSet bs = BitSet.valueOf(w);
            char[] d = new char[2 * runs];
            int n = 0;
            for (int s = bs.nextSetBit(0); s >= 0; ) {
                int e = bs.nextClearBit(s);
                d[n++] = (char) s;
                d[n++] = (char) (e - s - 1);
                s = bs.nextSetBit(e);
            }
            return new Run(d, card);
        }

        @Override String kind() { return "run"; }
        @Override int cardinality() { return card; }

        /** Index of the last run whose start is &lt;= v, or -1. */
        private int runBefore(int v) {
            int lo = 0, hi = d.length / 2 - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (d[2 * mid] <= v) { ans = mid; lo = mid + 1; } else hi = mid - 1;
            }
            return ans;
        }

        @Override
        boolean contains(int v) {
            int r = runBefore(v);
            return r >= 0 && v <= d[2 * r] + d[2 * r + 1];
        }

        @Override
        Container add(int v) {
            if (contains(v)) return this;
            long[] w = words();
            w[v >> 6] |= 1L << (v & 63);
            return fromWords(w);
        }

        @Override
        Container remove(int v) {
            if (!contains(v)) return this;
            long[] w = words();
            w[v >> 6] &= ~(1L << (v & 63));
            return fromWords(w);
        }

        @Override
        void fill(long[] w) {
            for (int i = 0; i < d.length; i += 2) applyRange(w, d[i], d[i] + d[i + 1], '+');
        }

        @Override
        void forEach(IntConsumer c) {
            for (int i = 0; i < d.length; i += 2) {
                for (int v = d[i], e = d[i] + d[i + 1]; v <= e; v++) c.accept(v);
            }
        }

        @Override Container copy() { return new Run(d.clone(), card); }
        @Override int sizeBytes() { return 2 + 2 * d.length; }

        @Override
        int rank(int v) {
            int n = 0;
            for (int i = 0; i < d.length; i += 2) {
                if (v < d[i]) break;
                n += Math.min(v, d[i] + d[i + 1]) - d[i] + 1;
            }
            return n;
        }

        @Override
        int select(int i) {
            int left = i;
            for (int k = 0; k < d.length; k += 2) {
                int len = d[k + 1] + 1;
                if (left < len) return d[k] + left;
                left -= len;
            }
            throw new IndexOutOfBoundsException("select " + i);
        }

        @Override
        void check() {
            if (d.length == 0) throw new IllegalStateException("empty run container");
            int c = 0;
            int prevEnd = -2;
            for (int i = 0; i < d.length; i += 2) {
                int s = d[i], e = d[i] + d[i + 1];
                if (s <= prevEnd + 1) throw new IllegalStateException("runs overlap or touch at " + i / 2);
                if (e > MAX) throw new IllegalStateException("run past 65535");
                c += e - s + 1;
                prevEnd = e;
            }
            if (c != card) throw new IllegalStateException("run card " + card + " but " + c);
        }
    }
}
