package ae.cc;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** ChaCha20-Poly1305 AEAD, RFC 8439 section 2.8. */
public final class Aead {
    private Aead() {}

    public static final int TAG_SIZE = 16;

    private static byte[] tag(byte[] key, byte[] nonce, byte[] aad, byte[] ct) {
        byte[] otk = Arrays.copyOf(ChaCha20.block(key, nonce, 0), 32);
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.writeBytes(aad);
        m.writeBytes(new byte[(16 - aad.length % 16) % 16]);
        m.writeBytes(ct);
        m.writeBytes(new byte[(16 - ct.length % 16) % 16]);
        m.writeBytes(Bytes.toLe(java.math.BigInteger.valueOf(aad.length), 8));
        m.writeBytes(Bytes.toLe(java.math.BigInteger.valueOf(ct.length), 8));
        return Poly1305.mac(otk, m.toByteArray());
    }

    /** Returns ciphertext || 16-byte tag. */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        Bytes.require(key, 32, "key");
        Bytes.require(nonce, 12, "nonce");
        Objects.requireNonNull(aad, "aad");
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] ct = ChaCha20.xor(key, nonce, 1, plaintext);
        byte[] t = tag(key, nonce, aad, ct);
        byte[] out = Arrays.copyOf(ct, ct.length + TAG_SIZE);
        System.arraycopy(t, 0, out, ct.length, TAG_SIZE);
        return out;
    }

    /** Plaintext, or empty if the input is too short or the tag does not verify. Nothing is decrypted before the tag check. */
    public static Optional<byte[]> open(byte[] key, byte[] nonce, byte[] aad, byte[] sealed) {
        Bytes.require(key, 32, "key");
        Bytes.require(nonce, 12, "nonce");
        Objects.requireNonNull(aad, "aad");
        Objects.requireNonNull(sealed, "sealed");
        if (sealed.length < TAG_SIZE) {
            return Optional.empty();
        }
        int n = sealed.length - TAG_SIZE;
        byte[] ct = Arrays.copyOf(sealed, n);
        byte[] got = Arrays.copyOfRange(sealed, n, sealed.length);
        if (!java.security.MessageDigest.isEqual(tag(key, nonce, aad, ct), got)) {
            return Optional.empty();
        }
        return Optional.of(ChaCha20.xor(key, nonce, 1, ct));
    }
}
