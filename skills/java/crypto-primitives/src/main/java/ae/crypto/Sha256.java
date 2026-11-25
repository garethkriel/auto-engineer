package ae.crypto;

import java.util.Objects;

/** Streaming SHA-256 (FIPS 180-4). Not thread-safe. {@link #digest()} finalises and resets the instance. */
public final class Sha256 {
    public static final int BLOCK_SIZE = 64;
    public static final int DIGEST_SIZE = 32;

    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };
    private static final int[] H0 = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };

    private int[] h = H0.clone();
    private byte[] buf = new byte[BLOCK_SIZE];
    private int bufLen;
    private long totalBytes;

    /** One-shot hash. */
    public static byte[] hash(byte[] data) {
        Sha256 s = new Sha256();
        s.update(data);
        return s.digest();
    }

    public void update(byte[] data) {
        Objects.requireNonNull(data, "data");
        update(data, 0, data.length);
    }

    public void update(byte[] data, int off, int len) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(off, len, data.length);
        totalBytes += len;
        int pos = off;
        int remaining = len;
        if (bufLen > 0) {
            int take = Math.min(remaining, BLOCK_SIZE - bufLen);
            System.arraycopy(data, pos, buf, bufLen, take);
            bufLen += take;
            pos += take;
            remaining -= take;
            if (bufLen == BLOCK_SIZE) {
                compress(buf, 0);
                bufLen = 0;
            }
        }
        while (remaining >= BLOCK_SIZE) {
            compress(data, pos);
            pos += BLOCK_SIZE;
            remaining -= BLOCK_SIZE;
        }
        if (remaining > 0) {
            System.arraycopy(data, pos, buf, 0, remaining);
            bufLen = remaining;
        }
    }

    /** Finishes the hash, returns the 32-byte digest and resets this instance to its initial state. */
    public byte[] digest() {
        long bitLen = totalBytes * 8;
        // Padding: 0x80, zeros until length % 64 == 56, then the 8-byte big-endian bit length.
        int padLen = (bufLen < 56) ? 56 - bufLen : 120 - bufLen;
        byte[] pad = new byte[padLen + 8];
        pad[0] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            pad[padLen + i] = (byte) (bitLen >>> (56 - 8 * i));
        }
        update(pad);
        byte[] out = new byte[DIGEST_SIZE];
        for (int i = 0; i < 8; i++) {
            out[4 * i] = (byte) (h[i] >>> 24);
            out[4 * i + 1] = (byte) (h[i] >>> 16);
            out[4 * i + 2] = (byte) (h[i] >>> 8);
            out[4 * i + 3] = (byte) h[i];
        }
        reset();
        return out;
    }

    /** Returns an independent copy holding the same absorbed state. */
    public Sha256 copy() {
        Sha256 c = new Sha256();
        c.h = h.clone();
        c.buf = buf.clone();
        c.bufLen = bufLen;
        c.totalBytes = totalBytes;
        return c;
    }

    private void reset() {
        h = H0.clone();
        bufLen = 0;
        totalBytes = 0;
    }

    private void compress(byte[] data, int off) {
        int[] w = new int[64];
        for (int i = 0; i < 16; i++) {
            int p = off + 4 * i;
            w[i] = (data[p] & 0xff) << 24 | (data[p + 1] & 0xff) << 16 | (data[p + 2] & 0xff) << 8 | (data[p + 3] & 0xff);
        }
        for (int i = 16; i < 64; i++) {
            int s0 = Integer.rotateRight(w[i - 15], 7) ^ Integer.rotateRight(w[i - 15], 18) ^ (w[i - 15] >>> 3);
            int s1 = Integer.rotateRight(w[i - 2], 17) ^ Integer.rotateRight(w[i - 2], 19) ^ (w[i - 2] >>> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        int a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; i++) {
            int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
            int ch = (e & f) ^ (~e & g);
            int t1 = hh + s1 + ch + K[i] + w[i];
            int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
            int maj = (a & b) ^ (a & c) ^ (b & c);
            int t2 = s0 + maj;
            hh = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        h[0] += a;
        h[1] += b;
        h[2] += c;
        h[3] += d;
        h[4] += e;
        h[5] += f;
        h[6] += g;
        h[7] += hh;
    }
}
