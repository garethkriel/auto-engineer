package ae.chain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. */
public final class Hashing {
    private Hashing() {
    }

    /** Returns the lower-case hex SHA-256 digest of the bytes. */
    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the lower-case hex SHA-256 digest of the UTF-8 text. */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Counts the leading '0' characters of a hex string. */
    public static int leadingZeroHexDigits(String hex) {
        int n = 0;
        while (n < hex.length() && hex.charAt(n) == '0') {
            n++;
        }
        return n;
    }
}
