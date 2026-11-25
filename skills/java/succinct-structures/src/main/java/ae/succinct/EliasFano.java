package ae.succinct;

import java.util.Objects;

/**
 * Elias-Fano encoding of a non-decreasing sequence of {@code n} values in {@code [0, universe]}:
 * about {@code n * (2 + log2(universe / n))} bits, with random access and successor search.
 *
 * <p>Each value is split at {@code l = floor(log2(universe / n))}: the low {@code l} bits are packed
 * in a plain array, the high part {@code v >> l} goes into a unary-coded bit vector of
 * {@code n + (universe >> l) + 1} bits where element {@code i} sets bit {@code (v >> l) + i}.
 */
public final class EliasFano {
    private final int n;
    private final long universe;
    private final int l;
    private final long[] low;
    private final BitVector high;

    public EliasFano(long[] sorted, long universe) {
        Objects.requireNonNull(sorted, "sorted");
        if (universe < 0) throw new IllegalArgumentException("negative universe " + universe);
        long prev = 0;
        for (long v : sorted) {
            if (v < prev) throw new IllegalArgumentException("sequence not non-decreasing or negative at value " + v);
            if (v > universe) throw new IllegalArgumentException("value " + v + " above universe " + universe);
            prev = v;
        }
        this.n = sorted.length;
        this.universe = universe;
        if (n == 0) {
            this.l = 0;
            this.low = new long[0];
            this.high = BitVector.of(new boolean[0]);
            return;
        }
        long ratio = universe / n;
        this.l = ratio <= 1 ? 0 : 63 - Long.numberOfLeadingZeros(ratio);
        long lowBits = (long) n * l;
        this.low = new long[(int) ((lowBits + 63) >>> 6)];
        long highBits = (long) n + (universe >>> l) + 1;
        if (highBits > Integer.MAX_VALUE) throw new IllegalArgumentException("sequence too large");
        BitVector.Builder hb = new BitVector.Builder((int) highBits);
        long mask = l == 0 ? 0 : (1L << l) - 1;
        for (int i = 0; i < n; i++) {
            long v = sorted[i];
            hb.set((int) ((v >>> l) + i));
            if (l > 0) putLow(i, v & mask);
        }
        this.high = hb.build();
    }

    private void putLow(int i, long value) {
        long bit = (long) i * l;
        int w = (int) (bit >>> 6);
        int off = (int) (bit & 63);
        low[w] |= value << off;
        if (off + l > 64) low[w + 1] |= value >>> (64 - off);
    }

    private long getLow(int i) {
        if (l == 0) return 0;
        long bit = (long) i * l;
        int w = (int) (bit >>> 6);
        int off = (int) (bit & 63);
        long v = low[w] >>> off;
        if (off + l > 64) v |= low[w + 1] << (64 - off);
        return v & ((1L << l) - 1);
    }

    public int size() {
        return n;
    }

    public long get(int i) {
        if (i < 0 || i >= n) throw new IndexOutOfBoundsException("index " + i + " of " + n);
        return ((long) (high.select1(i) - i) << l) | getLow(i);
    }

    /** Index of the first element {@code >= x}, or {@code size()} if there is none. */
    public int nextGeq(long x) {
        if (n == 0 || x > universe) return n;
        if (x <= 0) return 0;
        long h = x >>> l; // 1 <= h <= universe >> l whenever x >= 2^l; may be 0 for small x
        int i = 0;
        if (h > 0) {
            // elements whose high part is < h are the ones before the (h-1)-th zero of the upper vector
            int zeroPos = high.select0((int) (h - 1));
            i = zeroPos - (int) (h - 1);
        }
        while (i < n && get(i) < x) i++;
        return i;
    }

    public boolean contains(long x) {
        int i = nextGeq(x);
        return i < n && get(i) == x;
    }

    /** Payload bits: packed low parts plus the upper bit vector (directory excluded). */
    public long bitsUsed() {
        return n == 0 ? 0 : (long) n * l + high.size();
    }
}
