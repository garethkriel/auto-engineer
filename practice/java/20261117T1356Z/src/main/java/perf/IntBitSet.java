package perf;

import java.util.Arrays;

/** Fixed-capacity bit set over a {@code long[]} with rank/select support. */
public final class IntBitSet {
    private final int size;
    private final long[] words;

    public IntBitSet(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size < 0: " + size);
        }
        this.size = size;
        this.words = new long[(size + 63) >>> 6];
    }

    public int size() {
        return size;
    }

    private void check(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    public void set(int i) {
        check(i);
        words[i >>> 6] |= 1L << i; // shift count is taken mod 64
    }

    public void clear(int i) {
        check(i);
        words[i >>> 6] &= ~(1L << i);
    }

    public void flip(int i) {
        check(i);
        words[i >>> 6] ^= 1L << i;
    }

    public boolean get(int i) {
        check(i);
        return (words[i >>> 6] & (1L << i)) != 0;
    }

    public int cardinality() {
        int n = 0;
        for (long w : words) {
            n += Long.bitCount(w);
        }
        return n;
    }

    /** Index of the first set bit at or after {@code from}, or -1 if none. */
    public int nextSetBit(int from) {
        if (from < 0) {
            throw new IndexOutOfBoundsException("from < 0: " + from);
        }
        if (from >= size) {
            return -1;
        }
        int wi = from >>> 6;
        long w = words[wi] & (-1L << from);
        while (true) {
            if (w != 0) {
                return (wi << 6) + Long.numberOfTrailingZeros(w);
            }
            if (++wi == words.length) {
                return -1;
            }
            w = words[wi];
        }
    }

    /** Number of set bits in {@code [0, i)}. */
    public int rank(int i) {
        if (i < 0 || i > size) {
            throw new IndexOutOfBoundsException("rank " + i + ", size " + size);
        }
        int full = i >>> 6;
        int n = 0;
        for (int w = 0; w < full; w++) {
            n += Long.bitCount(words[w]);
        }
        int rem = i & 63;
        if (rem != 0) {
            n += Long.bitCount(words[full] & ((1L << rem) - 1));
        }
        return n;
    }

    /** Index of the k-th (0-based) set bit, or -1 if there are not that many. */
    public int select(int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k < 0: " + k);
        }
        int remaining = k;
        for (int wi = 0; wi < words.length; wi++) {
            long w = words[wi];
            int c = Long.bitCount(w);
            if (remaining < c) {
                for (int j = 0; j < remaining; j++) {
                    w &= w - 1; // drop lowest set bit
                }
                return (wi << 6) + Long.numberOfTrailingZeros(w);
            }
            remaining -= c;
        }
        return -1;
    }

    private void sameSize(IntBitSet o) {
        if (o.size != size) {
            throw new IllegalArgumentException("size mismatch: " + size + " vs " + o.size);
        }
    }

    public IntBitSet and(IntBitSet o) {
        sameSize(o);
        for (int i = 0; i < words.length; i++) {
            words[i] &= o.words[i];
        }
        return this;
    }

    public IntBitSet or(IntBitSet o) {
        sameSize(o);
        for (int i = 0; i < words.length; i++) {
            words[i] |= o.words[i];
        }
        return this;
    }

    public IntBitSet andNot(IntBitSet o) {
        sameSize(o);
        for (int i = 0; i < words.length; i++) {
            words[i] &= ~o.words[i];
        }
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof IntBitSet o && o.size == size && Arrays.equals(words, o.words);
    }

    @Override
    public int hashCode() {
        return 31 * size + Arrays.hashCode(words);
    }
}
