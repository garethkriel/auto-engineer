package ae.crypto;

import java.util.Arrays;
import java.util.Objects;

/** HMAC-SHA256 (RFC 2104). */
public final class Hmac {
    private Hmac() {}

    public static byte[] sha256(byte[] key, byte[] message) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(message, "message");
        byte[] k = key.length > Sha256.BLOCK_SIZE ? Sha256.hash(key) : key;
        byte[] ipad = new byte[Sha256.BLOCK_SIZE];
        byte[] opad = new byte[Sha256.BLOCK_SIZE];
        Arrays.fill(ipad, (byte) 0x36);
        Arrays.fill(opad, (byte) 0x5c);
        for (int i = 0; i < k.length; i++) {
            ipad[i] ^= k[i];
            opad[i] ^= k[i];
        }
        Sha256 inner = new Sha256();
        inner.update(ipad);
        inner.update(message);
        Sha256 outer = new Sha256();
        outer.update(opad);
        outer.update(inner.digest());
        return outer.digest();
    }
}
