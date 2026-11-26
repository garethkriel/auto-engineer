package ae.hash;

import java.util.Objects;
import java.util.function.Supplier;

/** PBKDF2 (RFC 8018) with HMAC over any {@link Digest}. */
public final class Pbkdf2 {
    private Pbkdf2() {}

    public static byte[] derive(Supplier<Digest> hash, byte[] password, byte[] salt, int iterations, int dkLen) {
        Objects.requireNonNull(password);
        Objects.requireNonNull(salt);
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be >= 1");
        }
        if (dkLen < 1) {
            throw new IllegalArgumentException("dkLen must be >= 1");
        }
        Hmac prf = new Hmac(hash, password);
        int hLen = prf.macLength();
        byte[] out = new byte[dkLen];
        int blocks = (dkLen + hLen - 1) / hLen;
        for (int i = 1; i <= blocks; i++) {
            prf.update(salt);
            prf.update(new byte[] {(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
            byte[] u = prf.doFinal();
            byte[] t = u.clone();
            for (int c = 1; c < iterations; c++) {
                prf.update(u);
                u = prf.doFinal();
                for (int k = 0; k < t.length; k++) {
                    t[k] ^= u[k];
                }
            }
            int n = Math.min(hLen, dkLen - (i - 1) * hLen);
            System.arraycopy(t, 0, out, (i - 1) * hLen, n);
        }
        return out;
    }
}
