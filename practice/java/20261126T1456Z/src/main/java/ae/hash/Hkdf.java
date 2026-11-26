package ae.hash;

import java.util.Objects;
import java.util.function.Supplier;

/** HKDF (RFC 5869). */
public final class Hkdf {
    private Hkdf() {}

    /** PRK = HMAC(salt, ikm); a null or empty salt means hashLen zero bytes. */
    public static byte[] extract(Supplier<Digest> hash, byte[] salt, byte[] ikm) {
        byte[] s = salt == null || salt.length == 0 ? new byte[hash.get().digestLength()] : salt;
        return Hmac.mac(hash, s, ikm);
    }

    /** OKM of {@code length} bytes: T(i) = HMAC(prk, T(i-1) || info || i). */
    public static byte[] expand(Supplier<Digest> hash, byte[] prk, byte[] info, int length) {
        Objects.requireNonNull(info);
        int hashLen = hash.get().digestLength();
        if (length < 0 || length > 255 * hashLen) {
            throw new IllegalArgumentException("length must be 0.." + 255 * hashLen);
        }
        byte[] okm = new byte[length];
        Hmac h = new Hmac(hash, prk);
        byte[] t = new byte[0];
        int done = 0;
        for (int i = 1; done < length; i++) {
            h.update(t);
            h.update(info);
            h.update(new byte[] {(byte) i});
            t = h.doFinal();
            int n = Math.min(t.length, length - done);
            System.arraycopy(t, 0, okm, done, n);
            done += n;
        }
        return okm;
    }
}
