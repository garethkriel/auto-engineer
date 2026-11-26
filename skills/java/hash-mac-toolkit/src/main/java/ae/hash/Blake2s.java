package ae.hash;

import java.util.Arrays;
import java.util.Objects;

/** BLAKE2s (RFC 7693): 32-bit words, 64-byte blocks, 10 rounds, optional key and 1..32 byte output. */
public final class Blake2s implements Digest {
    private static final int BLOCK = 64;
    private static final int[] IV = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

    private final int[] h = new int[8];
    private final byte[] buf = new byte[BLOCK];
    private final byte[] key;
    private final int outLen;
    private int bufLen;
    private long counter;

    public Blake2s() {
        this(32, null);
    }

    /** @param key may be null or empty for unkeyed hashing (at most 32 bytes) */
    public Blake2s(int outLen, byte[] key) {
        if (outLen < 1 || outLen > 32) {
            throw new IllegalArgumentException("outLen must be 1..32");
        }
        this.key = key == null ? new byte[0] : key.clone();
        if (this.key.length > 32) {
            throw new IllegalArgumentException("key longer than 32 bytes");
        }
        this.outLen = outLen;
        reset();
    }

    @Override public int digestLength() { return outLen; }

    @Override public int blockLength() { return BLOCK; }

    @Override
    public void reset() {
        System.arraycopy(IV, 0, h, 0, 8);
        h[0] ^= 0x01010000 ^ (key.length << 8) ^ outLen;
        Arrays.fill(buf, (byte) 0);
        counter = 0;
        bufLen = 0;
        if (key.length > 0) {
            System.arraycopy(key, 0, buf, 0, key.length);
            bufLen = BLOCK;
        }
    }

    @Override
    public void update(byte[] data, int off, int len) {
        Objects.requireNonNull(data);
        Objects.checkFromIndexSize(off, len, data.length);
        while (len > 0) {
            if (bufLen == BLOCK) {
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
            out[i] = (byte) (h[i >>> 2] >>> (8 * (i & 3)));
        }
        reset();
        return out;
    }

    private void compress(boolean last) {
        int[] m = new int[16];
        for (int i = 0; i < 16; i++) {
            m[i] = (buf[4 * i] & 0xff) | (buf[4 * i + 1] & 0xff) << 8
                    | (buf[4 * i + 2] & 0xff) << 16 | (buf[4 * i + 3] & 0xff) << 24;
        }
        int[] v = new int[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= (int) counter;
        v[13] ^= (int) (counter >>> 32);
        if (last) {
            v[14] = ~v[14];
        }
        for (int r = 0; r < 10; r++) {
            int[] s = Blake2b.SIGMA[r];
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

    private static void g(int[] v, int a, int b, int c, int d, int x, int y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
        v[a] = v[a] + v[b] + y;
        v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
        v[c] = v[c] + v[d];
        v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
    }
}
