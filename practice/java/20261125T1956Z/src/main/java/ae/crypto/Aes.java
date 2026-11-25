package ae.crypto;

import java.util.Objects;

/** AES block cipher (FIPS 197) with 128, 192 and 256-bit keys. The S-box is computed, not typed in. */
public final class Aes {
    public static final int BLOCK_SIZE = 16;

    static final byte[] SBOX = new byte[256];
    static final byte[] INV_SBOX = new byte[256];

    static {
        // Walk the multiplicative group of GF(2^8) with generator 3 (p) and its inverse (q).
        int p = 1;
        int q = 1;
        do {
            p = (p ^ (p << 1) ^ ((p & 0x80) != 0 ? 0x11b : 0)) & 0xff; // p *= 3
            q ^= q << 1; // q /= 3
            q ^= q << 2;
            q ^= q << 4;
            q &= 0xff;
            if ((q & 0x80) != 0) {
                q ^= 0x09;
            }
            int x = q ^ rotl8(q, 1) ^ rotl8(q, 2) ^ rotl8(q, 3) ^ rotl8(q, 4);
            SBOX[p] = (byte) (x ^ 0x63);
        } while (p != 1);
        SBOX[0] = 0x63;
        for (int i = 0; i < 256; i++) {
            INV_SBOX[SBOX[i] & 0xff] = (byte) i;
        }
    }

    private static int rotl8(int x, int n) {
        return ((x << n) | (x >>> (8 - n))) & 0xff;
    }

    private static int xtime(int a) {
        return ((a << 1) ^ ((a & 0x80) != 0 ? 0x1b : 0)) & 0xff;
    }

    private static int mul(int a, int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            if ((b & 1) != 0) {
                r ^= a;
            }
            a = xtime(a);
            b >>>= 1;
        }
        return r;
    }

    private final int rounds;
    private final byte[] roundKeys;

    public Aes(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalArgumentException("AES key must be 16, 24 or 32 bytes, got " + key.length);
        }
        int nk = key.length / 4;
        rounds = nk + 6;
        int words = 4 * (rounds + 1);
        roundKeys = new byte[words * 4];
        System.arraycopy(key, 0, roundKeys, 0, key.length);
        int rcon = 1;
        for (int i = nk; i < words; i++) {
            byte[] t = new byte[4];
            System.arraycopy(roundKeys, 4 * (i - 1), t, 0, 4);
            if (i % nk == 0) {
                byte t0 = t[0];
                t[0] = (byte) (SBOX[t[1] & 0xff] ^ rcon);
                t[1] = SBOX[t[2] & 0xff];
                t[2] = SBOX[t[3] & 0xff];
                t[3] = SBOX[t0 & 0xff];
                rcon = xtime(rcon);
            } else if (nk > 6 && i % nk == 4) {
                for (int j = 0; j < 4; j++) {
                    t[j] = SBOX[t[j] & 0xff];
                }
            }
            for (int j = 0; j < 4; j++) {
                roundKeys[4 * i + j] = (byte) (roundKeys[4 * (i - nk) + j] ^ t[j]);
            }
        }
    }

    public void encryptBlock(byte[] in, int inOff, byte[] out, int outOff) {
        byte[] s = load(in, inOff, out, outOff);
        addRoundKey(s, 0);
        for (int r = 1; r < rounds; r++) {
            subBytes(s, SBOX);
            shiftRows(s);
            mixColumns(s, 2, 3, 1, 1);
            addRoundKey(s, r);
        }
        subBytes(s, SBOX);
        shiftRows(s);
        addRoundKey(s, rounds);
        System.arraycopy(s, 0, out, outOff, BLOCK_SIZE);
    }

    public void decryptBlock(byte[] in, int inOff, byte[] out, int outOff) {
        byte[] s = load(in, inOff, out, outOff);
        addRoundKey(s, rounds);
        for (int r = rounds - 1; r >= 1; r--) {
            invShiftRows(s);
            subBytes(s, INV_SBOX);
            addRoundKey(s, r);
            mixColumns(s, 14, 11, 13, 9);
        }
        invShiftRows(s);
        subBytes(s, INV_SBOX);
        addRoundKey(s, 0);
        System.arraycopy(s, 0, out, outOff, BLOCK_SIZE);
    }

    private static byte[] load(byte[] in, int inOff, byte[] out, int outOff) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.checkFromIndexSize(inOff, BLOCK_SIZE, in.length);
        Objects.checkFromIndexSize(outOff, BLOCK_SIZE, out.length);
        byte[] s = new byte[BLOCK_SIZE];
        System.arraycopy(in, inOff, s, 0, BLOCK_SIZE);
        return s;
    }

    private void addRoundKey(byte[] s, int round) {
        for (int i = 0; i < 16; i++) {
            s[i] ^= roundKeys[16 * round + i];
        }
    }

    private static void subBytes(byte[] s, byte[] box) {
        for (int i = 0; i < 16; i++) {
            s[i] = box[s[i] & 0xff];
        }
    }

    // State is column-major: s[4*c + r]. Row r rotates left by r columns.
    private static void shiftRows(byte[] s) {
        byte[] t = s.clone();
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                s[4 * c + r] = t[4 * ((c + r) % 4) + r];
            }
        }
    }

    private static void invShiftRows(byte[] s) {
        byte[] t = s.clone();
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                s[4 * ((c + r) % 4) + r] = t[4 * c + r];
            }
        }
    }

    /** Column multiply by the circulant matrix (a b c c) with a,b,c,c' = m0..m3 ordering: (m0 m1 m2 m3). */
    private static void mixColumns(byte[] s, int m0, int m1, int m2, int m3) {
        for (int c = 0; c < 4; c++) {
            int a0 = s[4 * c] & 0xff, a1 = s[4 * c + 1] & 0xff, a2 = s[4 * c + 2] & 0xff, a3 = s[4 * c + 3] & 0xff;
            s[4 * c] = (byte) (mul(a0, m0) ^ mul(a1, m1) ^ mul(a2, m2) ^ mul(a3, m3));
            s[4 * c + 1] = (byte) (mul(a0, m3) ^ mul(a1, m0) ^ mul(a2, m1) ^ mul(a3, m2));
            s[4 * c + 2] = (byte) (mul(a0, m2) ^ mul(a1, m3) ^ mul(a2, m0) ^ mul(a3, m1));
            s[4 * c + 3] = (byte) (mul(a0, m1) ^ mul(a1, m2) ^ mul(a2, m3) ^ mul(a3, m0));
        }
    }
}
