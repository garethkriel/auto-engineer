package ae.hash;

import java.util.Arrays;
import java.util.Objects;

/** BLAKE2b (RFC 7693): 64-bit words, 128-byte blocks, 12 rounds, optional key and 1..64 byte output. */
public final class Blake2b implements Digest {
    private static final int BLOCK = 128;
    private static final long[] IV = {
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L};
    static final int[][] SIGMA = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
        {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
        {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
        {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
        {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
        {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
        {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
        {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
        {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

    private final long[] h = new long[8];
    private final byte[] buf = new byte[BLOCK];
    private final byte[] key;
    private final int outLen;
    private int bufLen;
    private long counter;

    public Blake2b() {
        this(64, null);
    }

    /** @param key may be null or empty for unkeyed hashing (at most 64 bytes) */
    public Blake2b(int outLen, byte[] key) {
        if (outLen < 1 || outLen > 64) {
            throw new IllegalArgumentException("outLen must be 1..64");
        }
        this.key = key == null ? new byte[0] : key.clone();
        if (this.key.length > 64) {
            throw new IllegalArgumentException("key longer than 64 bytes");
        }
        this.outLen = outLen;
        reset();
    }

    @Override public int digestLength() { return outLen; }

    @Override public int blockLength() { return BLOCK; }

    @Override
    public void reset() {
        System.arraycopy(IV, 0, h, 0, 8);
        h[0] ^= 0x01010000L ^ ((long) key.length << 8) ^ outLen;
        Arrays.fill(buf, (byte) 0);
        counter = 0;
        bufLen = 0;
        if (key.length > 0) {
            System.arraycopy(key, 0, buf, 0, key.length);
            bufLen = BLOCK;  // the key block is compressed like data, but only once more input follows
        }
    }

    @Override
    public void update(byte[] data, int off, int len) {
        Objects.requireNonNull(data);
        Objects.checkFromIndexSize(off, len, data.length);
        while (len > 0) {
            if (bufLen == BLOCK) {  // a full buffer is only flushed when more input arrives (last block flag)
                counter += BLOCK;
                compress(false);
                bufLen = 0;
            }
            int n = Math.min(len, BLOCK - bufLen);
            System.arraycopy(data, off, buf, bufLen, n);
            bufLen += n;
            off += n;
            len -= n;
        }
    }

    @Override
    public byte[] digest() {
        counter += bufLen;
        Arrays.fill(buf, bufLen, BLOCK, (byte) 0);
        compress(true);
        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) {
            out[i] = (byte) (h[i >>> 3] >>> (8 * (i & 7)));
        }
        reset();
        return out;
    }

    private void compress(boolean last) {
        long[] m = new long[16];
        for (int i = 0; i < 16; i++) {
            long w = 0;
            for (int b = 7; b >= 0; b--) {
                w = w << 8 | (buf[8 * i + b] & 0xffL);
            }
            m[i] = w;
        }
        long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= counter;  // high counter word stays 0: inputs are limited to 2^63 bytes
        if (last) {
            v[14] = ~v[14];
        }
        for (int r = 0; r < 12; r++) {
            int[] s = SIGMA[r % 10];
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    private static void g(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }
}
