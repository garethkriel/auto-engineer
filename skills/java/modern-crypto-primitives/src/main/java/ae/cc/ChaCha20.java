package ae.cc;

import java.util.Objects;

/** ChaCha20 stream cipher, RFC 8439 (96-bit nonce, 32-bit block counter). */
public final class ChaCha20 {
    private ChaCha20() {}

    private static final int[] SIGMA = {0x61707865, 0x3320646e, 0x79622d32, 0x6b206574};

    private static int le32(byte[] a, int o) {
        return (a[o] & 0xff) | (a[o + 1] & 0xff) << 8 | (a[o + 2] & 0xff) << 16 | (a[o + 3] & 0xff) << 24;
    }

    private static void qr(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    /** One 64-byte keystream block. {@code counter} is an unsigned 32-bit value held in an int. */
    public static byte[] block(byte[] key, byte[] nonce, int counter) {
        Bytes.require(key, 32, "key");
        Bytes.require(nonce, 12, "nonce");
        int[] st = new int[16];
        System.arraycopy(SIGMA, 0, st, 0, 4);
        for (int i = 0; i < 8; i++) st[4 + i] = le32(key, 4 * i);
        st[12] = counter;
        for (int i = 0; i < 3; i++) st[13 + i] = le32(nonce, 4 * i);
        int[] x = st.clone();
        for (int round = 0; round < 10; round++) {
            qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13); qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15);
            qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12); qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14);
        }
        byte[] out = new byte[64];
        for (int i = 0; i < 16; i++) {
            int v = x[i] + st[i];
            out[4 * i] = (byte) v;
            out[4 * i + 1] = (byte) (v >>> 8);
            out[4 * i + 2] = (byte) (v >>> 16);
            out[4 * i + 3] = (byte) (v >>> 24);
        }
        return out;
    }

    /** XOR {@code data} with the keystream starting at block {@code counter}; the counter may not wrap. */
    public static byte[] xor(byte[] key, byte[] nonce, int counter, byte[] data) {
        Bytes.require(key, 32, "key");
        Bytes.require(nonce, 12, "nonce");
        Objects.requireNonNull(data, "data");
        long blocks = (data.length + 63L) / 64;
        long first = counter & 0xffffffffL;
        if (first + blocks > 0x1_0000_0000L) {
            throw new IllegalArgumentException("block counter would wrap");
        }
        byte[] out = new byte[data.length];
        for (int pos = 0, c = counter; pos < data.length; pos += 64, c++) {
            byte[] ks = block(key, nonce, c);
            int n = Math.min(64, data.length - pos);
            for (int i = 0; i < n; i++) out[pos + i] = (byte) (data[pos + i] ^ ks[i]);
        }
        return out;
    }
}
