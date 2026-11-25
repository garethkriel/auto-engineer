package ae.succinct;

import java.util.Objects;

/**
 * Wavelet matrix over non-negative {@code int} values: {@code access}, {@code rank}, {@code select},
 * k-th smallest and value-range counting on arbitrary subranges in O(log sigma) rank operations.
 *
 * <p>Level 0 stores the most significant bit of each value in sequence order. The next level's order
 * is the stable partition of the current one: elements whose bit is 0 first, then those with 1.
 * {@code zeros[level]} is the number of zero bits at that level, so an index {@code i} maps to
 * {@code rank0(i)} when the bit is 0 and {@code zeros + rank1(i)} when it is 1.
 */
public final class WaveletMatrix {
    private final int n;
    private final int bits;
    private final BitVector[] level;
    private final int[] zeros;
    private final long maxValue;

    public WaveletMatrix(int[] values) {
        Objects.requireNonNull(values, "values");
        int max = 0;
        for (int v : values) {
            if (v < 0) throw new IllegalArgumentException("negative value " + v);
            max = Math.max(max, v);
        }
        this.n = values.length;
        this.maxValue = max;
        this.bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(max));
        this.level = new BitVector[bits];
        this.zeros = new int[bits];
        int[] cur = values.clone();
        int[] next = new int[n];
        for (int l = 0; l < bits; l++) {
            int shift = bits - 1 - l;
            BitVector.Builder b = new BitVector.Builder(n);
            int z = 0;
            for (int i = 0; i < n; i++) {
                if ((cur[i] >>> shift & 1) == 0) z++;
                else b.set(i);
            }
            level[l] = b.build();
            zeros[l] = z;
            int zi = 0, oi = z;
            for (int i = 0; i < n; i++) {
                if ((cur[i] >>> shift & 1) == 0) next[zi++] = cur[i];
                else next[oi++] = cur[i];
            }
            int[] t = cur;
            cur = next;
            next = t;
        }
    }

    /** Test hook: copy of {@code w} whose {@code zeros[lvl]} is increased by {@code delta}. */
    static WaveletMatrix corruptZeros(WaveletMatrix w, int lvl, int delta) {
        WaveletMatrix c = new WaveletMatrix(new int[0]);
        return c.copyOf(w, lvl, delta, w.level);
    }

    /** Test hook: copy of {@code w} whose level 0 has {@code len} bits. */
    static WaveletMatrix corruptLength(WaveletMatrix w, int len) {
        BitVector[] lv = w.level.clone();
        lv[0] = BitVector.of(new boolean[len]);
        return new WaveletMatrix(new int[0]).copyOf(w, 0, 0, lv);
    }

    private WaveletMatrix copyOf(WaveletMatrix w, int lvl, int delta, BitVector[] lv) {
        int[] z = w.zeros.clone();
        z[lvl] += delta;
        return new WaveletMatrix(w.n, w.bits, lv, z, w.maxValue);
    }

    private WaveletMatrix(int n, int bits, BitVector[] level, int[] zeros, long maxValue) {
        this.n = n;
        this.bits = bits;
        this.level = level;
        this.zeros = zeros;
        this.maxValue = maxValue;
    }

    public int length() {
        return n;
    }

    public int access(int i) {
        if (i < 0 || i >= n) throw new IndexOutOfBoundsException("index " + i + " of " + n);
        int v = 0;
        for (int l = 0; l < bits; l++) {
            BitVector bv = level[l];
            if (bv.get(i)) {
                v = v << 1 | 1;
                i = zeros[l] + bv.rank1(i);
            } else {
                v <<= 1;
                i = bv.rank0(i);
            }
        }
        return v;
    }

    private boolean representable(long v) {
        return v >= 0 && v <= maxValue;
    }

    /** Occurrences of {@code v} in positions {@code [0, i)}. */
    public int rank(int v, int i) {
        if (i < 0 || i > n) throw new IndexOutOfBoundsException("rank position " + i + " of " + n);
        if (!representable(v)) return 0;
        int s = 0, e = i;
        for (int l = 0; l < bits; l++) {
            BitVector bv = level[l];
            if ((v >>> (bits - 1 - l) & 1) == 0) {
                s = bv.rank0(s);
                e = bv.rank0(e);
            } else {
                s = zeros[l] + bv.rank1(s);
                e = zeros[l] + bv.rank1(e);
            }
        }
        return e - s;
    }

    /** Position of the k-th (0-based) occurrence of {@code v}. */
    public int select(int v, int k) {
        if (k < 0 || !representable(v) || k >= rank(v, n)) {
            throw new IndexOutOfBoundsException("no occurrence #" + k + " of " + v);
        }
        int s = 0;
        for (int l = 0; l < bits; l++) {
            BitVector bv = level[l];
            s = (v >>> (bits - 1 - l) & 1) == 0 ? bv.rank0(s) : zeros[l] + bv.rank1(s);
        }
        int pos = s + k; // index among the elements ordered by the last level
        for (int l = bits - 1; l >= 0; l--) {
            BitVector bv = level[l];
            pos = (v >>> (bits - 1 - l) & 1) == 0 ? bv.select0(pos) : bv.select1(pos - zeros[l]);
        }
        return pos;
    }

    private void checkRange(int l, int r) {
        if (l < 0 || r > n || l > r) throw new IndexOutOfBoundsException("range [" + l + "," + r + ") of " + n);
    }

    /** The k-th smallest (0-based) value among positions {@code [l, r)}. */
    public int kthSmallest(int l, int r, int k) {
        checkRange(l, r);
        if (k < 0 || k >= r - l) throw new IndexOutOfBoundsException("k=" + k + " in range of " + (r - l));
        int v = 0;
        for (int lv = 0; lv < bits; lv++) {
            BitVector bv = level[lv];
            int l0 = bv.rank0(l), r0 = bv.rank0(r);
            if (k < r0 - l0) {
                l = l0;
                r = r0;
            } else {
                k -= r0 - l0;
                v |= 1 << (bits - 1 - lv);
                l = zeros[lv] + (l - l0);
                r = zeros[lv] + (r - r0);
            }
        }
        return v;
    }

    /** Number of values {@code < x} among positions {@code [l, r)}. */
    public int countLess(int l, int r, int x) {
        checkRange(l, r);
        if (x <= 0) return 0;
        if (x > maxValue) return r - l;
        int count = 0;
        for (int lv = 0; lv < bits; lv++) {
            BitVector bv = level[lv];
            int l0 = bv.rank0(l), r0 = bv.rank0(r);
            if ((x >>> (bits - 1 - lv) & 1) == 1) {
                count += r0 - l0; // everything with a 0 here is smaller
                l = zeros[lv] + (l - l0);
                r = zeros[lv] + (r - r0);
            } else {
                l = l0;
                r = r0;
            }
        }
        return count;
    }

    /** Number of values {@code v} with {@code lo <= v < hi} among positions {@code [l, r)}. */
    public int rangeCount(int l, int r, int lo, int hi) {
        if (hi <= lo) {
            checkRange(l, r);
            return 0;
        }
        return countLess(l, r, hi) - countLess(l, r, lo);
    }

    /** Verifies per-level lengths and zero counts; throws {@link IllegalStateException}. */
    public void checkInvariants() {
        for (int l = 0; l < bits; l++) {
            if (level[l].size() != n) {
                throw new IllegalStateException("wavelet: level length " + level[l].size() + " at level " + l + " expected " + n);
            }
            level[l].checkInvariants();
            if (zeros[l] != n - level[l].ones()) {
                throw new IllegalStateException("wavelet: zeros " + zeros[l] + " at level " + l + " but vector has " + (n - level[l].ones()));
            }
        }
    }
}
