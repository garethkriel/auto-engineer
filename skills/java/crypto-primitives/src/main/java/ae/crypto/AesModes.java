package ae.crypto;

import java.util.Arrays;
import java.util.Objects;

/** CBC (PKCS#7), CTR and GCM (96-bit IV, 128-bit tag) over {@link Aes}. */
public final class AesModes {
    private static final int TAG_SIZE = 16;
    private static final int GCM_IV_SIZE = 12;

    private AesModes() {}

    public static byte[] cbcEncrypt(byte[] key, byte[] iv, byte[] plaintext) {
        Aes aes = new Aes(key);
        checkIv(iv, 16);
        Objects.requireNonNull(plaintext, "plaintext");
        int pad = 16 - plaintext.length % 16;
        byte[] padded = Arrays.copyOf(plaintext, plaintext.length + pad);
        Arrays.fill(padded, plaintext.length, padded.length, (byte) pad);
        byte[] out = new byte[padded.length];
        byte[] prev = iv.clone();
        for (int off = 0; off < padded.length; off += 16) {
            for (int i = 0; i < 16; i++) {
                prev[i] ^= padded[off + i];
            }
            aes.encryptBlock(prev, 0, out, off);
            System.arraycopy(out, off, prev, 0, 16);
        }
        return out;
    }

    /** Every malformed input (length or padding) fails with the same {@code "bad padding"} message. */
    public static byte[] cbcDecrypt(byte[] key, byte[] iv, byte[] ciphertext) {
        Aes aes = new Aes(key);
        checkIv(iv, 16);
        Objects.requireNonNull(ciphertext, "ciphertext");
        if (ciphertext.length == 0 || ciphertext.length % 16 != 0) {
            throw new CryptoException("bad padding");
        }
        byte[] plain = new byte[ciphertext.length];
        byte[] prev = iv;
        for (int off = 0; off < ciphertext.length; off += 16) {
            aes.decryptBlock(ciphertext, off, plain, off);
            for (int i = 0; i < 16; i++) {
                plain[off + i] ^= prev[i];
            }
            prev = Arrays.copyOfRange(ciphertext, off, off + 16);
        }
        int pad = plain[plain.length - 1] & 0xff;
        // Check every padding byte without an early exit.
        int bad = (pad == 0 || pad > 16) ? 1 : 0;
        int checked = Math.min(pad, 16);
        for (int i = 1; i <= checked; i++) {
            bad |= (plain[plain.length - i] & 0xff) ^ pad;
        }
        if (bad != 0) {
            throw new CryptoException("bad padding");
        }
        return Arrays.copyOf(plain, plain.length - pad);
    }

    /** CTR with a full 128-bit big-endian counter starting at {@code iv}; encryption and decryption are the same. */
    public static byte[] ctr(byte[] key, byte[] iv, byte[] data) {
        Aes aes = new Aes(key);
        checkIv(iv, 16);
        Objects.requireNonNull(data, "data");
        return ctrCore(aes, iv.clone(), data, false);
    }

    private static byte[] ctrCore(Aes aes, byte[] counter, byte[] data, boolean inc32) {
        byte[] out = new byte[data.length];
        byte[] ks = new byte[16];
        for (int off = 0; off < data.length; off += 16) {
            aes.encryptBlock(counter, 0, ks, 0);
            int n = Math.min(16, data.length - off);
            for (int i = 0; i < n; i++) {
                out[off + i] = (byte) (data[off + i] ^ ks[i]);
            }
            increment(counter, inc32 ? 12 : 0);
        }
        return out;
    }

    private static void increment(byte[] counter, int from) {
        for (int i = counter.length - 1; i >= from; i--) {
            if (++counter[i] != 0) {
                return;
            }
        }
    }

    /** Returns ciphertext followed by the 16-byte tag. */
    public static byte[] gcmEncrypt(byte[] key, byte[] iv, byte[] aad, byte[] plaintext) {
        Aes aes = new Aes(key);
        checkIv(iv, GCM_IV_SIZE);
        Objects.requireNonNull(aad, "aad");
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] j0 = j0(iv);
        byte[] first = j0.clone();
        increment(first, 12);
        byte[] ct = ctrCore(aes, first, plaintext, true);
        byte[] tag = tag(aes, j0, aad, ct);
        byte[] out = Arrays.copyOf(ct, ct.length + TAG_SIZE);
        System.arraycopy(tag, 0, out, ct.length, TAG_SIZE);
        return out;
    }

    /** Verifies the tag (constant time) before decrypting anything; failure message is {@code "bad tag"}. */
    public static byte[] gcmDecrypt(byte[] key, byte[] iv, byte[] aad, byte[] sealed) {
        Aes aes = new Aes(key);
        checkIv(iv, GCM_IV_SIZE);
        Objects.requireNonNull(aad, "aad");
        Objects.requireNonNull(sealed, "sealed");
        if (sealed.length < TAG_SIZE) {
            throw new CryptoException("bad tag");
        }
        byte[] ct = Arrays.copyOf(sealed, sealed.length - TAG_SIZE);
        byte[] given = Arrays.copyOfRange(sealed, sealed.length - TAG_SIZE, sealed.length);
        byte[] j0 = j0(iv);
        if (!ConstantTime.equals(given, tag(aes, j0, aad, ct))) {
            throw new CryptoException("bad tag");
        }
        byte[] first = j0.clone();
        increment(first, 12);
        return ctrCore(aes, first, ct, true);
    }

    private static byte[] j0(byte[] iv) {
        byte[] j0 = new byte[16];
        System.arraycopy(iv, 0, j0, 0, GCM_IV_SIZE);
        j0[15] = 1;
        return j0;
    }

    private static byte[] tag(Aes aes, byte[] j0, byte[] aad, byte[] ct) {
        byte[] h = new byte[16];
        aes.encryptBlock(new byte[16], 0, h, 0);
        long hHi = be64(h, 0), hLo = be64(h, 8);
        long[] y = {0, 0};
        ghashBytes(y, hHi, hLo, aad);
        ghashBytes(y, hHi, hLo, ct);
        y[0] ^= (long) aad.length * 8;
        y[1] ^= (long) ct.length * 8;
        mulH(y, hHi, hLo);
        byte[] ek = new byte[16];
        aes.encryptBlock(j0, 0, ek, 0);
        byte[] t = new byte[16];
        for (int i = 0; i < 8; i++) {
            t[i] = (byte) (ek[i] ^ (y[0] >>> (56 - 8 * i)));
            t[8 + i] = (byte) (ek[8 + i] ^ (y[1] >>> (56 - 8 * i)));
        }
        return t;
    }

    private static void ghashBytes(long[] y, long hHi, long hLo, byte[] data) {
        for (int off = 0; off < data.length; off += 16) {
            byte[] block = new byte[16];
            System.arraycopy(data, off, block, 0, Math.min(16, data.length - off));
            y[0] ^= be64(block, 0);
            y[1] ^= be64(block, 8);
            mulH(y, hHi, hLo);
        }
    }

    /** y = y * H in GF(2^128) with the GCM bit order (bit 0 is the most significant bit of the first byte). */
    private static void mulH(long[] y, long hHi, long hLo) {
        long zHi = 0, zLo = 0;
        long vHi = hHi, vLo = hLo;
        for (int i = 0; i < 128; i++) {
            long bit = i < 64 ? (y[0] >>> (63 - i)) & 1 : (y[1] >>> (127 - i)) & 1;
            if (bit != 0) {
                zHi ^= vHi;
                zLo ^= vLo;
            }
            boolean lsb = (vLo & 1) != 0;
            vLo = (vLo >>> 1) | (vHi << 63);
            vHi >>>= 1;
            if (lsb) {
                vHi ^= 0xe100000000000000L;
            }
        }
        y[0] = zHi;
        y[1] = zLo;
    }

    private static long be64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xff);
        }
        return v;
    }

    private static void checkIv(byte[] iv, int expected) {
        Objects.requireNonNull(iv, "iv");
        if (iv.length != expected) {
            throw new IllegalArgumentException("iv must be " + expected + " bytes, got " + iv.length);
        }
    }
}
