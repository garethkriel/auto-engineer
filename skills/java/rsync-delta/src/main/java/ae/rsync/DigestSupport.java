package ae.rsync;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class DigestSupport {
    private DigestSupport() {}

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
