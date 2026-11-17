package ae.sst;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Fixed-size Bloom filter over byte strings with double hashing. Serialised as {@code [k u8][words u32][longs]}. */
public final class BloomFilter {
    static final int BITS_PER_KEY = 10;
    static final int HASHES = 7;

    private final int hashes;
    private final long[] words;

    private BloomFilter(int hashes, long[] words) {
        this.hashes = hashes;
        this.words = words;
    }

    /** A filter sized for {@code expected} keys at 10 bits per key (at least one 64-bit word). */
    public static BloomFilter forCount(int expected) {
        if (expected < 0) throw new IllegalArgumentException("negative count");
        long bits = Math.max(64L, (long) expected * BITS_PER_KEY);
        int w = (int) Math.min(Integer.MAX_VALUE / 2, (bits + 63) / 64);
        return new BloomFilter(HASHES, new long[w]);
    }

    public static BloomFilter fromBytes(byte[] data) {
        if (data.length < 5) throw new IllegalArgumentException("bloom block too short");
        ByteBuffer bb = ByteBuffer.wrap(data);
        int k = bb.get() & 0xFF;
        int w = bb.getInt();
        if (k < 1 || k > 32) throw new IllegalArgumentException("bad hash count " + k);
        if (w < 1 || (long) w * 8 != data.length - 5L) throw new IllegalArgumentException("bad word count " + w);
        long[] ws = new long[w];
        for (int i = 0; i < w; i++) ws[i] = bb.getLong();
        return new BloomFilter(k, ws);
    }

    public byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(5 + words.length * 8);
        bb.put((byte) hashes).putInt(words.length);
        for (long w : words) bb.putLong(w);
        return bb.array();
    }

    public void add(byte[] key) {
        long h1 = fnv(key);
        long h2 = mix(h1) | 1L;
        long m = (long) words.length * 64;
        for (int i = 0; i < hashes; i++) {
            long bit = Math.floorMod(h1 + i * h2, m);
            words[(int) (bit >>> 6)] |= 1L << (bit & 63);
        }
    }

    public boolean mightContain(byte[] key) {
        long h1 = fnv(key);
        long h2 = mix(h1) | 1L;
        long m = (long) words.length * 64;
        for (int i = 0; i < hashes; i++) {
            long bit = Math.floorMod(h1 + i * h2, m);
            if ((words[(int) (bit >>> 6)] & (1L << (bit & 63))) == 0) return false;
        }
        return true;
    }

    private static long fnv(byte[] key) {
        long h = 0xcbf29ce484222325L;
        for (byte x : key) {
            h ^= x & 0xFF;
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BloomFilter f && hashes == f.hashes && Arrays.equals(words, f.words);
    }

    @Override
    public int hashCode() {
        return 31 * hashes + Arrays.hashCode(words);
    }
}
