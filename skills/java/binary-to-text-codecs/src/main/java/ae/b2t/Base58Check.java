package ae.b2t;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Base58Check: version byte + payload + 4-byte double-SHA-256 checksum. */
public final class Base58Check {
    private Base58Check() { }

    /** Decoded form. */
    public record Decoded(int version, byte[] payload) { }

    public static String encode(int version, byte[] payload) {
        Objects.requireNonNull(payload);
        if (version < 0 || version > 255) {
            throw new IllegalArgumentException("version out of range");
        }
        byte[] full = new byte[payload.length + 5];
        full[0] = (byte) version;
        System.arraycopy(payload, 0, full, 1, payload.length);
        byte[] sum = checksum(full, payload.length + 1);
        System.arraycopy(sum, 0, full, payload.length + 1, 4);
        return Base58.encode(full);
    }

    public static Decoded decode(String s) {
        byte[] raw = Base58.decode(s);
        if (raw.length < 5) {
            throw new IllegalArgumentException("too short");
        }
        int bodyLen = raw.length - 4;
        byte[] sum = checksum(raw, bodyLen);
        if (!MessageDigest.isEqual(sum, Arrays.copyOfRange(raw, bodyLen, raw.length))) {
            throw new IllegalArgumentException("bad checksum");
        }
        return new Decoded(raw[0] & 0xff, Arrays.copyOfRange(raw, 1, bodyLen));
    }

    private static byte[] checksum(byte[] data, int len) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(data, 0, len);
            byte[] h = sha.digest(sha.digest());
            return Arrays.copyOf(h, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
