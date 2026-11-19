package ae.sketch;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Bloom filter using Kirsch-Mitzenmacher double hashing. Not thread safe. */
public final class BloomFilter {
    static final int MAGIC = 0x424c4f4d; // "BLOM"
    private static final long SEED_A = 0x1234L;
    private static final long SEED_B = 0x9876L;

    private final int bitCount;
    private final int hashCount;
    private final long[] words;

    private BloomFilter(int bitCount, int hashCount, long[] words) {
        this.bitCount = bitCount;
        this.hashCount = hashCount;
        this.words = words;
    }

    /** Creates a filter sized for {@code expected} items at false-positive probability {@code fpp}. */
    public static BloomFilter forExpected(long expected, double fpp) {
        if (expected <= 0) {
            throw new IllegalArgumentException("expected must be positive");
        }
        if (!(fpp > 0.0 && fpp < 1.0)) {
            throw new IllegalArgumentException("fpp must be in (0,1)");
        }
        double ln2 = Math.log(2);
        double m = Math.ceil(-expected * Math.log(fpp) / (ln2 * ln2));
        if (m > Integer.MAX_VALUE - 64) {
            throw new IllegalArgumentException("filter too large");
        }
        int bits = (int) m;
        int k = Math.max(1, (int) Math.round(bits / (double) expected * ln2));
        return withShape(bits, k);
    }

    /** Creates an empty filter with explicit shape. */
    public static BloomFilter withShape(int bitCount, int hashCount) {
        if (bitCount < 1 || hashCount < 1) {
            throw new IllegalArgumentException("bitCount and hashCount must be positive");
        }
        return new BloomFilter(bitCount, hashCount, new long[(bitCount + 63) >>> 6]);
    }

    public int bitCount() {
        return bitCount;
    }

    public int hashCount() {
        return hashCount;
    }

    public void add(String key) {
        add(Objects.requireNonNull(key, "key").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void add(byte[] key) {
        long h1 = Hash64.hash(key, SEED_A);
        long h2 = Hash64.hash(key, SEED_B) | 1L;
        for (int i = 0; i < hashCount; i++) {
            int idx = index(h1, h2, i);
            words[idx >>> 6] |= 1L << (idx & 63);
        }
    }

    public boolean mightContain(String key) {
        return mightContain(Objects.requireNonNull(key, "key").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public boolean mightContain(byte[] key) {
        long h1 = Hash64.hash(key, SEED_A);
        long h2 = Hash64.hash(key, SEED_B) | 1L;
        for (int i = 0; i < hashCount; i++) {
            int idx = index(h1, h2, i);
            if ((words[idx >>> 6] & (1L << (idx & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    private int index(long h1, long h2, int i) {
        return (int) Long.remainderUnsigned(h1 + i * h2, bitCount);
    }

    /** Number of set bits. */
    public int setBits() {
        int c = 0;
        for (long w : words) {
            c += Long.bitCount(w);
        }
        return c;
    }

    /** Swamidass-Baldi cardinality estimate; infinity if every bit is set. */
    public double estimatedCount() {
        int x = setBits();
        if (x >= bitCount) {
            return Double.POSITIVE_INFINITY;
        }
        return -(bitCount / (double) hashCount) * Math.log(1.0 - x / (double) bitCount);
    }

    /** Returns a new filter holding the union of both; shapes must match. */
    public BloomFilter union(BloomFilter other) {
        Objects.requireNonNull(other, "other");
        if (other.bitCount != bitCount || other.hashCount != hashCount) {
            throw new IllegalArgumentException("shape mismatch");
        }
        long[] w = new long[words.length];
        for (int i = 0; i < w.length; i++) {
            w[i] = words[i] | other.words[i];
        }
        return new BloomFilter(bitCount, hashCount, w);
    }

    public byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(4 + 8 + words.length * 8);
        b.putInt(MAGIC).putInt(bitCount).putInt(hashCount);
        for (long w : words) {
            b.putLong(w);
        }
        return Codec.seal(b.array());
    }

    public static BloomFilter fromBytes(byte[] image) {
        ByteBuffer b = Codec.open(image, MAGIC, 8);
        int bits = b.getInt();
        int k = b.getInt();
        if (bits < 1 || k < 1) {
            throw new IllegalArgumentException("bad shape");
        }
        int wordCount = (bits + 63) >>> 6;
        if (b.remaining() != wordCount * 8) {
            throw new IllegalArgumentException("length does not match shape");
        }
        long[] w = new long[wordCount];
        for (int i = 0; i < wordCount; i++) {
            w[i] = b.getLong();
        }
        int tail = bits & 63;
        if (tail != 0 && (w[wordCount - 1] >>> tail) != 0) {
            throw new IllegalArgumentException("bits set beyond filter size");
        }
        return new BloomFilter(bits, k, w);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BloomFilter f && f.bitCount == bitCount && f.hashCount == hashCount
            && Arrays.equals(f.words, words);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * bitCount + hashCount) + Arrays.hashCode(words);
    }
}
