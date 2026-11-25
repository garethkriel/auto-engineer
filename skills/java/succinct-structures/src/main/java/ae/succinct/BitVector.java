package ae.succinct;

import java.util.Arrays;

/**
 * Immutable bit vector with constant-time {@code rank} and logarithmic {@code select}.
 *
 * <p>Layout: bits live in {@code long[]} words (bit {@code i} is bit {@code i & 63} of word
 * {@code i >> 6}, least significant first). A directory holds, for every superblock of
 * {@value #SUPER_BITS} bits, the number of ones before it, plus a final entry with the total
 * (so its length is {@code ceil(n / 512) + 1}). Padding bits beyond {@code n} are always zero.
 */
public final class BitVector {
    static final int WORDS_PER_SUPER = 8;
    static final int SUPER_BITS = WORDS_PER_SUPER * 64;

    private final long[] words;
    private final int n;
    private final int[] dir;

    private BitVector(long[] words, int n, int[] dir) {
        this.words = words;
        this.n = n;
        this.dir = dir;
    }

    /** Package-private constructor with a caller-supplied directory, for corruption tests. */
    static BitVector unchecked(long[] words, int n, int[] dir) {
        return new BitVector(words, n, dir);
    }

    private static int blocks(int n) {
        return (n + SUPER_BITS - 1) / SUPER_BITS;
    }

    private static BitVector fromWords(long[] words, int n) {
        int[] dir = new int[blocks(n) + 1];
        int total = 0;
        for (int b = 0; b < dir.length - 1; b++) {
            dir[b] = total;
            int end = Math.min(words.length, (b + 1) * WORDS_PER_SUPER);
            for (int w = b * WORDS_PER_SUPER; w < end; w++) total += Long.bitCount(words[w]);
        }
        dir[dir.length - 1] = total;
        return new BitVector(words, n, dir);
    }

    /** Builds a vector from a boolean array. */
    public static BitVector of(boolean[] bits) {
        Builder b = new Builder(bits.length);
        for (int i = 0; i < bits.length; i++) if (bits[i]) b.set(i);
        return b.build();
    }

    /** Mutable builder; the built vector is a snapshot and unaffected by later calls. */
    public static final class Builder {
        private final int n;
        private final long[] words;

        public Builder(int n) {
            if (n < 0) throw new IllegalArgumentException("negative size " + n);
            this.n = n;
            this.words = new long[(n + 63) >>> 6];
        }

        public Builder set(int i) {
            if (i < 0 || i >= n) throw new IndexOutOfBoundsException("bit " + i + " of " + n);
            words[i >>> 6] |= 1L << (i & 63);
            return this;
        }

        public BitVector build() {
            return fromWords(words.clone(), n);
        }
    }

    public int size() {
        return n;
    }

    public int ones() {
        return dir[dir.length - 1];
    }

    public boolean get(int i) {
        if (i < 0 || i >= n) throw new IndexOutOfBoundsException("bit " + i + " of " + n);
        return (words[i >>> 6] >>> (i & 63) & 1L) != 0;
    }

    /** Number of ones in {@code [0, i)}, for {@code 0 <= i <= size()}. */
    public int rank1(int i) {
        if (i < 0 || i > n) throw new IndexOutOfBoundsException("rank position " + i + " of " + n);
        int w = i >>> 6;
        int count = dir[i / SUPER_BITS];
        for (int j = (i / SUPER_BITS) * WORDS_PER_SUPER; j < w; j++) count += Long.bitCount(words[j]);
        int r = i & 63;
        if (r != 0) count += Long.bitCount(words[w] & ((1L << r) - 1));
        return count;
    }

    /** Number of zeros in {@code [0, i)}. */
    public int rank0(int i) {
        return i - rank1(i);
    }

    /** Position of the k-th (0-based) one. */
    public int select1(int k) {
        if (k < 0 || k >= ones()) throw new IndexOutOfBoundsException("select1(" + k + ") with " + ones() + " ones");
        // last block whose prefix count is <= k
        int lo = 0, hi = dir.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (dir[mid] <= k) lo = mid;
            else hi = mid - 1;
        }
        int remaining = k - dir[lo];
        for (int w = lo * WORDS_PER_SUPER; ; w++) {
            int c = Long.bitCount(words[w]);
            if (remaining < c) return (w << 6) + selectInWord(words[w], remaining);
            remaining -= c;
        }
    }

    /** Position of the k-th (0-based) zero. */
    public int select0(int k) {
        int zeros = n - ones();
        if (k < 0 || k >= zeros) throw new IndexOutOfBoundsException("select0(" + k + ") with " + zeros + " zeros");
        // zeros before block b = b * SUPER_BITS - dir[b]
        int lo = 0, hi = dir.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (mid * SUPER_BITS - dir[mid] <= k) lo = mid;
            else hi = mid - 1;
        }
        int remaining = k - (lo * SUPER_BITS - dir[lo]);
        for (int w = lo * WORDS_PER_SUPER; ; w++) {
            long inverted = ~words[w];
            int c = Long.bitCount(inverted);
            if (remaining < c) return (w << 6) + selectInWord(inverted, remaining);
            remaining -= c;
        }
    }

    /** Position of the k-th (0-based) set bit of {@code word}; requires {@code k < bitCount(word)}. */
    static int selectInWord(long word, int k) {
        for (int i = 0; i < k; i++) word &= word - 1; // clear the lowest set bit
        return Long.numberOfTrailingZeros(word);
    }

    /** Bits used by the directory (not the payload). */
    long directoryBits() {
        return 32L * dir.length;
    }

    /** Verifies the directory and the padding bits; throws {@link IllegalStateException}. */
    public void checkInvariants() {
        if (words.length != (n + 63) >>> 6) {
            throw new IllegalStateException("bitvector: word count " + words.length + " for n=" + n);
        }
        if (dir.length != blocks(n) + 1) {
            throw new IllegalStateException("bitvector: directory length " + dir.length + " for n=" + n);
        }
        if ((n & 63) != 0 && words.length > 0 && (words[words.length - 1] >>> (n & 63)) != 0) {
            throw new IllegalStateException("bitvector: padding bits set beyond n=" + n);
        }
        int total = 0;
        for (int b = 0; b < dir.length - 1; b++) {
            if (dir[b] != total) {
                throw new IllegalStateException("bitvector: block " + b + " starts with " + dir[b] + " but has " + total);
            }
            int end = Math.min(words.length, (b + 1) * WORDS_PER_SUPER);
            for (int w = b * WORDS_PER_SUPER; w < end; w++) total += Long.bitCount(words[w]);
        }
        if (dir[dir.length - 1] != total) {
            throw new IllegalStateException("bitvector: total " + dir[dir.length - 1] + " but counted " + total);
        }
    }

    @Override
    public String toString() {
        return "BitVector(n=" + n + ", ones=" + ones() + ", words=" + Arrays.hashCode(words) + ")";
    }
}
