package ae.hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.function.Supplier;

/** Shared helpers: chain-of-lengths fixture, random chunked hashing and a JDK digest adapter. */
final class TestUtil {
    private TestUtil() {}

    /** Message of length n with bytes (i*7+3) mod 256 (same generator as the Python literal script). */
    static byte[] msg(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 7 + 3);
        }
        return b;
    }

    /** digest(concat(digest(msg(0)), ..., digest(msg(300)))) using one reused instance. */
    static String chain(Digest d) {
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        for (int n = 0; n <= 300; n++) {
            d.update(msg(n));
            byte[] h = d.digest();
            all.write(h, 0, h.length);
        }
        d.update(all.toByteArray());
        return Hex.encode(d.digest());
    }

    static String hashStr(Digest d, String s) {
        d.update(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return Hex.encode(d.digest());
    }

    /** Feeds data in random chunk sizes (including zero-length chunks). */
    static byte[] chunked(Digest d, byte[] data, Random rnd) {
        int pos = 0;
        while (pos < data.length) {
            int len = Math.min(data.length - pos, rnd.nextInt(Math.max(1, 3 * d.blockLength())));
            d.update(data, pos, len);
            pos += len;
            if (rnd.nextInt(4) == 0) {
                d.update(new byte[0], 0, 0);
            }
        }
        return d.digest();
    }

    /** Adapter that lets HMAC/HKDF/PBKDF2 run over a JDK digest (SHA-256 / SHA-512 / SHA3). */
    static final class JdkDigest implements Digest {
        private final MessageDigest md;
        private final int block;

        JdkDigest(String alg, int block) {
            try {
                this.md = MessageDigest.getInstance(alg);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            this.block = block;
        }

        @Override public int digestLength() { return md.getDigestLength(); }
        @Override public int blockLength() { return block; }
        @Override public void update(byte[] b, int off, int len) { md.update(b, off, len); }
        @Override public byte[] digest() { return md.digest(); }
        @Override public void reset() { md.reset(); }
    }

    static Supplier<Digest> sha256() { return () -> new JdkDigest("SHA-256", 64); }
    static Supplier<Digest> sha512() { return () -> new JdkDigest("SHA-512", 128); }
}
