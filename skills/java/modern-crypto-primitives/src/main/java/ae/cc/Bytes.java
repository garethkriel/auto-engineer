package ae.cc;

import java.math.BigInteger;
import java.util.Objects;

/** Small shared helpers: length checks and little-endian BigInteger conversion. */
final class Bytes {
    private Bytes() {}

    static byte[] require(byte[] a, int len, String name) {
        Objects.requireNonNull(a, name);
        if (a.length != len) {
            throw new IllegalArgumentException(name + " must be " + len + " bytes, got " + a.length);
        }
        return a;
    }

    /** Unsigned little-endian bytes to a non-negative integer. */
    static BigInteger fromLe(byte[] a, int off, int len) {
        byte[] be = new byte[len];
        for (int i = 0; i < len; i++) {
            be[len - 1 - i] = a[off + i];
        }
        return new BigInteger(1, be);
    }

    /** Non-negative integer to exactly {@code len} little-endian bytes (high bytes beyond len are dropped). */
    static byte[] toLe(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }
}
