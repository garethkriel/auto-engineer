package ae.crypto;

import java.util.Arrays;
import java.util.Objects;

/** Key derivation: HKDF (RFC 5869) and PBKDF2 (RFC 8018), both over HMAC-SHA256. */
public final class Kdf {
    private Kdf() {}

    /** HKDF-Extract; an empty salt means 32 zero bytes. */
    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(ikm, "ikm");
        return Hmac.sha256(salt.length == 0 ? new byte[Sha256.DIGEST_SIZE] : salt, ikm);
    }

    /** HKDF-Expand to {@code length} bytes, 0 &lt;= length &lt;= 255 * 32. */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        Objects.requireNonNull(prk, "prk");
        Objects.requireNonNull(info, "info");
        if (length < 0 || length > 255 * Sha256.DIGEST_SIZE) {
            throw new IllegalArgumentException("length out of range: " + length);
        }
        byte[] out = new byte[length];
        byte[] prev = new byte[0];
        int produced = 0;
        for (int counter = 1; produced < length; counter++) {
            byte[] in = new byte[prev.length + info.length + 1];
            System.arraycopy(prev, 0, in, 0, prev.length);
            System.arraycopy(info, 0, in, prev.length, info.length);
            in[in.length - 1] = (byte) counter;
            prev = Hmac.sha256(prk, in);
            int take = Math.min(prev.length, length - produced);
            System.arraycopy(prev, 0, out, produced, take);
            produced += take;
        }
        return out;
    }

    /** PBKDF2-HMAC-SHA256. */
    public static byte[] pbkdf2Sha256(byte[] password, byte[] salt, int iterations, int dkLen) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(salt, "salt");
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be >= 1");
        }
        if (dkLen < 1) {
            throw new IllegalArgumentException("dkLen must be >= 1");
        }
        byte[] out = new byte[dkLen];
        int blocks = (dkLen + Sha256.DIGEST_SIZE - 1) / Sha256.DIGEST_SIZE;
        for (int i = 1; i <= blocks; i++) {
            byte[] first = Arrays.copyOf(salt, salt.length + 4);
            first[salt.length] = (byte) (i >>> 24);
            first[salt.length + 1] = (byte) (i >>> 16);
            first[salt.length + 2] = (byte) (i >>> 8);
            first[salt.length + 3] = (byte) i;
            byte[] u = Hmac.sha256(password, first);
            byte[] t = u.clone();
            for (int j = 1; j < iterations; j++) {
                u = Hmac.sha256(password, u);
                for (int k = 0; k < t.length; k++) {
                    t[k] ^= u[k];
                }
            }
            int off = (i - 1) * Sha256.DIGEST_SIZE;
            System.arraycopy(t, 0, out, off, Math.min(t.length, dkLen - off));
        }
        return out;
    }
}
