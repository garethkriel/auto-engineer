package ae.cc;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Objects;

/** Poly1305 one-time authenticator, RFC 8439 section 2.5, over BigInteger. */
public final class Poly1305 {
    private Poly1305() {}

    private static final BigInteger P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
    private static final BigInteger CLAMP = new BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16);
    private static final BigInteger MOD128 = BigInteger.ONE.shiftLeft(128);

    /** 16-byte tag of {@code msg} under the 32-byte one-time key (r || s). */
    public static byte[] mac(byte[] key, byte[] msg) {
        Bytes.require(key, 32, "key");
        Objects.requireNonNull(msg, "msg");
        BigInteger r = Bytes.fromLe(key, 0, 16).and(CLAMP);
        BigInteger s = Bytes.fromLe(key, 16, 16);
        BigInteger acc = BigInteger.ZERO;
        for (int pos = 0; pos < msg.length; pos += 16) {
            int n = Math.min(16, msg.length - pos);
            BigInteger block = Bytes.fromLe(msg, pos, n).setBit(8 * n); // append the 0x01 byte
            acc = acc.add(block).multiply(r).mod(P);
        }
        return Bytes.toLe(acc.add(s).mod(MOD128), 16);
    }

    /** Constant-time tag comparison; a wrong-length tag is simply invalid. */
    public static boolean verify(byte[] key, byte[] msg, byte[] tag) {
        Objects.requireNonNull(tag, "tag");
        return MessageDigest.isEqual(mac(key, msg), tag);
    }
}
