package ae.pk;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Random;
import javax.crypto.KeyAgreement;
import org.junit.jupiter.api.Test;

class EcdsaTest {
    static final Curve C = Curve.P256;
    static final BigInteger RFC_X = h("C9AFA9D8 45BA7516 6B5C2157 67B1D693 4E50C3DB 36E89B12 7B8A622B 120F6721");

    static BigInteger h(String s) { return new BigInteger(s.replace(" ", ""), 16); }

    static byte[] sha(String m) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(m.getBytes(StandardCharsets.UTF_8));
    }

    static ECParameterSpec jdkParams() throws Exception {
        AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        return ap.getParameterSpec(ECParameterSpec.class);
    }

    static PublicKey jdkPub(Point q) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(q.x(), q.y()), jdkParams()));
    }

    static PrivateKey jdkPriv(BigInteger d) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, jdkParams()));
    }

    @Test
    void rfc6979PublicKey() {
        Point q = Ecdsa.publicKey(C, RFC_X);
        assertEquals(h("60FED4BA 255A9D31 C961EB74 C6356D68 C049B892 3B61FA6C E669622E 60F29FB6"), q.x());
        assertEquals(h("7903FE10 08B8BC99 A41AE9E9 5628BC64 F2F1B20C 2D7E9F51 77A3C294 D4462299"), q.y());
    }

    @Test
    void rfc6979NonceVectors() throws Exception {
        assertEquals(h("A6E3C57D D01ABE90 08653839 8355DD4C 3B17AA87 3382B0F2 4D612949 3D8AAD60"),
            Ecdsa.nonce(C, RFC_X, sha("sample")));
        assertEquals(h("D16B6AE8 27F17175 E040871A 1C7EC350 0192C4C9 2677336E C2537ACA EE0008E0"),
            Ecdsa.nonce(C, RFC_X, sha("test")));
    }

    @Test
    void rfc6979SignatureVectors() throws Exception {
        Ecdsa.Signature s1 = Ecdsa.sign(C, RFC_X, sha("sample"));
        assertEquals(h("EFD48B2A ACB6A8FD 1140DD9C D45E81D6 9D2C877B 56AAF991 C34D0EA8 4EAF3716"), s1.r());
        assertEquals(h("F7CB1C94 2D657C41 D436C7A1 B6E29F65 F3E900DB B9AFF406 4DC4AB2F 843ACDA8"), s1.s());
        Ecdsa.Signature s2 = Ecdsa.sign(C, RFC_X, sha("test"));
        assertEquals(h("F1ABB023 518351CD 71D88156 7B1EA663 ED3EFCF6 C5132B35 4F28D3B0 B7D38367"), s2.r());
        assertEquals(h("019F4113 742A2B14 BD25926B 49C64915 5F267E60 D3814B4C 0CC84250 E46F0083"), s2.s());
    }

    @Test
    void signVerifyRoundTripBothCurves() throws Exception {
        Random rnd = new Random(3);
        for (Curve c : new Curve[] {Curve.P256, Curve.SECP256K1}) {
            for (int i = 0; i < 4; i++) {
                BigInteger d = new BigInteger(255, rnd).add(BigInteger.ONE);
                Point q = Ecdsa.publicKey(c, d);
                byte[] hash = sha("m" + i);
                Ecdsa.Signature sig = Ecdsa.sign(c, d, hash);
                assertTrue(Ecdsa.verify(c, q, hash, sig), c.name());
                assertEquals(sig, Ecdsa.sign(c, d, hash), "deterministic");
                assertFalse(Ecdsa.verify(c, q, sha("other"), sig));
                assertFalse(Ecdsa.verify(c, Ecdsa.publicKey(c, d.add(BigInteger.ONE)), hash, sig));
            }
        }
    }

    @Test
    void oursVerifiesUnderJdkAndJdkUnderOurs() throws Exception {
        Random rnd = new Random(21);
        for (int i = 0; i < 5; i++) {
            BigInteger d = new BigInteger(255, rnd).add(BigInteger.ONE);
            Point q = Ecdsa.publicKey(C, d);
            String msg = "message number " + i;
            Ecdsa.Signature sig = Ecdsa.sign(C, d, sha(msg));
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(jdkPub(q));
            v.update(msg.getBytes(StandardCharsets.UTF_8));
            assertTrue(v.verify(Der.encode(sig)), "JDK accepts our DER signature");

            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(jdkPriv(d));
            s.update(msg.getBytes(StandardCharsets.UTF_8));
            Ecdsa.Signature theirs = Der.decode(s.sign());
            assertTrue(Ecdsa.verify(C, q, sha(msg), theirs), "we accept JDK signature");
        }
    }

    @Test
    void verifyRejectsOutOfRangeAndTamperedValues() throws Exception {
        Point q = Ecdsa.publicKey(C, RFC_X);
        byte[] hash = sha("sample");
        Ecdsa.Signature good = Ecdsa.sign(C, RFC_X, hash);
        assertTrue(Ecdsa.verify(C, q, hash, good), "control");
        BigInteger n = C.n();
        assertFalse(Ecdsa.verify(C, q, hash, new Ecdsa.Signature(BigInteger.ZERO, good.s())));
        assertFalse(Ecdsa.verify(C, q, hash, new Ecdsa.Signature(good.r(), BigInteger.ZERO)));
        assertFalse(Ecdsa.verify(C, q, hash, new Ecdsa.Signature(good.r().add(n), good.s())), "r + n alias");
        assertFalse(Ecdsa.verify(C, q, hash, new Ecdsa.Signature(good.r(), good.s().add(n))), "s + n alias");
        assertFalse(Ecdsa.verify(C, q, hash, new Ecdsa.Signature(n, good.s())));
        assertFalse(Ecdsa.verify(C, q, hash, new Ecdsa.Signature(good.r(), n)));
        assertFalse(Ecdsa.verify(C, q, hash, new Ecdsa.Signature(good.r().negate(), good.s())));
        for (int bit = 0; bit < 256; bit += 17) {
            assertFalse(Ecdsa.verify(C, q, hash,
                new Ecdsa.Signature(good.r().flipBit(bit).mod(n), good.s())), "r bit " + bit);
            assertFalse(Ecdsa.verify(C, q, hash,
                new Ecdsa.Signature(good.r(), good.s().flipBit(bit).mod(n))), "s bit " + bit);
        }
        assertThrows(IllegalArgumentException.class,
            () -> Ecdsa.verify(C, Point.INFINITY, hash, good), "infinity key");
        assertThrows(IllegalArgumentException.class,
            () -> Ecdsa.verify(C, new Point(q.x(), q.y().add(BigInteger.ONE)), hash, good), "off-curve key");
    }

    @Test
    void highSMalleabilityAndLowS() throws Exception {
        Point q = Ecdsa.publicKey(C, RFC_X);
        byte[] hash = sha("sample");
        Ecdsa.Signature sig = Ecdsa.sign(C, RFC_X, hash);
        Ecdsa.Signature flipped = new Ecdsa.Signature(sig.r(), C.n().subtract(sig.s()));
        assertTrue(Ecdsa.verify(C, q, hash, flipped), "(r, n-s) is a second valid signature");
        BigInteger half = C.n().shiftRight(1);
        assertTrue(sig.s().compareTo(half) > 0, "RFC vector s is high: fixture for lowS");
        Ecdsa.Signature low = Ecdsa.lowS(C, sig);
        assertEquals(flipped, low);
        assertEquals(low, Ecdsa.lowS(C, low), "idempotent");
        assertTrue(Ecdsa.verify(C, q, hash, low));
        Ecdsa.Signature s2 = Ecdsa.sign(C, RFC_X, sha("test")); // s = 0x019F... is already low
        assertEquals(s2, Ecdsa.lowS(C, s2));
    }

    @Test
    void signRejectsBadPrivateKeys() throws Exception {
        byte[] hash = sha("x");
        assertThrows(IllegalArgumentException.class, () -> Ecdsa.sign(C, BigInteger.ZERO, hash));
        assertThrows(IllegalArgumentException.class, () -> Ecdsa.sign(C, C.n(), hash));
        assertThrows(IllegalArgumentException.class, () -> Ecdsa.sign(C, BigInteger.ONE.negate(), hash));
        assertNotNull(Ecdsa.sign(C, C.n().subtract(BigInteger.ONE), hash), "n-1 is the largest valid key");
        assertNotNull(Ecdsa.sign(C, BigInteger.ONE, hash), "1 is the smallest valid key");
    }

    @Test
    void longHashIsTruncatedToLeftmostBits() throws Exception {
        // 64-byte hash: only the leftmost 256 bits are used (SEC1 / RFC 6979 bits2int)
        byte[] h64 = new byte[64];
        new Random(1).nextBytes(h64);
        byte[] h32 = java.util.Arrays.copyOf(h64, 32);
        Point q = Ecdsa.publicKey(C, RFC_X);
        Ecdsa.Signature sig = Ecdsa.sign(C, RFC_X, h64);
        assertEquals(Ecdsa.sign(C, RFC_X, h32), sig);
        assertTrue(Ecdsa.verify(C, q, h32, sig));
        byte[] h8 = new byte[] {1, 2, 3, 4, 5, 6, 7, 8}; // short hash: still usable (left-padded integer)
        assertTrue(Ecdsa.verify(C, q, h8, Ecdsa.sign(C, RFC_X, h8)));
    }

    @Test
    void ecdhAgreesAndMatchesJdk() throws Exception {
        Random rnd = new Random(8);
        for (int i = 0; i < 3; i++) {
            BigInteger a = new BigInteger(255, rnd).add(BigInteger.ONE);
            BigInteger b = new BigInteger(255, rnd).add(BigInteger.ONE);
            Point qa = Ecdsa.publicKey(C, a);
            Point qb = Ecdsa.publicKey(C, b);
            byte[] s1 = Ecdh.sharedSecret(C, a, qb);
            byte[] s2 = Ecdh.sharedSecret(C, b, qa);
            assertArrayEquals(s1, s2);
            assertEquals(32, s1.length);
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(jdkPriv(a));
            ka.doPhase(jdkPub(qb), true);
            assertArrayEquals(ka.generateSecret(), s1, "JDK agrees");
        }
    }

    @Test
    void ecdhRejectsInvalidInputs() {
        Point q = Ecdsa.publicKey(C, BigInteger.TEN);
        assertEquals(32, Ecdh.sharedSecret(C, BigInteger.TWO, q).length, "control");
        assertThrows(IllegalArgumentException.class, () -> Ecdh.sharedSecret(C, BigInteger.ZERO, q));
        assertThrows(IllegalArgumentException.class, () -> Ecdh.sharedSecret(C, C.n(), q));
        assertThrows(IllegalArgumentException.class, () -> Ecdh.sharedSecret(C, BigInteger.TWO, Point.INFINITY));
        assertThrows(IllegalArgumentException.class,
            () -> Ecdh.sharedSecret(C, BigInteger.TWO, new Point(q.x(), q.y().subtract(BigInteger.ONE))),
            "invalid-curve point");
    }

    @Test
    void hashAtLeastNIsReducedBeforeNonceDerivation() {
        // RFC 6979 bits2octets reduces the hash mod n: hash n+1 and hash 1 give identical nonces and signatures.
        byte[] one = Curve.i2osp(BigInteger.ONE, 32);
        byte[] nPlus1 = Curve.i2osp(C.n().add(BigInteger.ONE), 32);
        assertEquals(Ecdsa.nonce(C, RFC_X, one), Ecdsa.nonce(C, RFC_X, nPlus1));
        assertEquals(Ecdsa.sign(C, RFC_X, one), Ecdsa.sign(C, RFC_X, nPlus1));
        byte[] two = Curve.i2osp(BigInteger.TWO, 32);
        assertNotEquals(Ecdsa.nonce(C, RFC_X, one), Ecdsa.nonce(C, RFC_X, two), "control: hash value matters");
    }
}
