package ae.vcs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Identity of a stored object: lowercase hex SHA-256 of its raw bytes. */
public record ObjectId(String hex) implements Comparable<ObjectId> {
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    public ObjectId {
        if (hex == null || !HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("not a 64-char lowercase hex id: " + hex);
        }
    }

    /** Hashes raw object bytes. */
    public static ObjectId ofRaw(byte[] raw) {
        try {
            return new ObjectId(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectId parse(String text) {
        return new ObjectId(text.strip().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public int compareTo(ObjectId o) {
        return hex.compareTo(o.hex);
    }

    @Override
    public String toString() {
        return hex.substring(0, 12);
    }

    byte[] hexBytes() {
        return hex.getBytes(StandardCharsets.US_ASCII);
    }
}
