package ae.pk;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RsaTest {
    static final BigInteger E = BigInteger.valueOf(65537);
    static final Rsa.PrivateKey K512 = Rsa.generate(512, new Random(42));
    static final byte[] MSG = "attack at dawn".getBytes(StandardCharsets.UTF_8);

    static byte[] sha(byte[] m) throws Exception { return MessageDigest.getInstance("SHA-256").digest(m); }

    /** Raw private-key exponentiation without any padding checks, used to craft forged signatures. */
    static byte[] rawSign(Rsa.PrivateKey k, byte[] em) {
        BigInteger m = new BigInteger(1, em);
        return Curve.i2osp(m.modPow(k.d(), k.n()), (k.n().bitLength() + 7) / 8);
    }

    @Test
    void textbookToyKey() {
        // p=61, q=53: n=3233, lambda=lcm(60,52)=780, e=17, d=17^-1 mod 780 = 413
        Rsa.PrivateKey k = Rsa.fromPrimes(BigInteger.valueOf(61), BigInteger.valueOf(53), BigInteger.valueOf(17));
        assertEquals(BigInteger.valueOf(3233), k.n());
        assertEquals(BigInteger.valueOf(413), k.d());
        assertEquals(BigInteger.valueOf(413 % 60), k.dp());
        assertEquals(BigInteger.valueOf(413 % 52), k.dq());
        BigInteger c = Rsa.rsaep(k.publicKey(), BigInteger.valueOf(65));
        assertEquals(BigInteger.valueOf(2790), c, "65^17 mod 3233");
        assertEquals(BigInteger.valueOf(65), Rsa.rsadp(k, c));
        for (int m = 0; m < 3233; m++) {
            BigInteger bm = BigInteger.valueOf(m);
            assertEquals(bm, Rsa.rsadp(k, Rsa.rsaep(k.publicKey(), bm)), "m=" + m);
        }
        assertThrows(IllegalArgumentException.class, () -> Rsa.rsaep(k.publicKey(), BigInteger.valueOf(3233)));
        assertThrows(IllegalArgumentException.class, () -> Rsa.rsadp(k, BigInteger.valueOf(-1)));
    }

    @Test
    void fromPrimesRejectsBadParameters() {
        BigInteger p = BigInteger.valueOf(61);
        BigInteger q = BigInteger.valueOf(53);
        assertNotNull(Rsa.fromPrimes(p, q, BigInteger.valueOf(17)), "control");
        assertThrows(IllegalArgumentException.class, () -> Rsa.fromPrimes(p, p, BigInteger.valueOf(17)), "p == q");
        assertThrows(IllegalArgumentException.class, () -> Rsa.fromPrimes(p, q, BigInteger.valueOf(13)), "gcd(e, lambda) = 13");
        assertThrows(IllegalArgumentException.class, () -> Rsa.fromPrimes(p, q, BigInteger.valueOf(4)), "even e");
        assertThrows(IllegalArgumentException.class, () -> Rsa.fromPrimes(p, BigInteger.valueOf(55), BigInteger.valueOf(17)), "composite q");
    }

    @Test
    void generatedKeyIsConsistent() {
        Rsa.PrivateKey k = K512;
        assertEquals(512, k.n().bitLength());
        assertEquals(k.n(), k.p().multiply(k.q()));
        assertTrue(k.p().compareTo(k.q()) > 0, "p > q convention");
        assertEquals(E, k.e());
        BigInteger lambda = k.p().subtract(BigInteger.ONE).divide(k.p().subtract(BigInteger.ONE).gcd(k.q().subtract(BigInteger.ONE)))
            .multiply(k.q().subtract(BigInteger.ONE));
        assertEquals(BigInteger.ONE, k.d().multiply(E).mod(lambda));
        assertEquals(k.d().mod(k.p().subtract(BigInteger.ONE)), k.dp());
        assertEquals(k.d().mod(k.q().subtract(BigInteger.ONE)), k.dq());
        assertEquals(BigInteger.ONE, k.qinv().multiply(k.q()).mod(k.p()));
        assertTrue(k.p().isProbablePrime(40) && k.q().isProbablePrime(40));
        assertEquals(k.n(), Rsa.generate(512, new Random(42)).n(), "seeded generation is reproducible");
        assertNotEquals(k.n(), Rsa.generate(512, new Random(43)).n());
        assertThrows(IllegalArgumentException.class, () -> Rsa.generate(511, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> Rsa.generate(256, new Random(1)));
    }

    @Test
    void generates1024BitKeyThatSignsAndVerifies() throws Exception {
        Rsa.PrivateKey k = Rsa.generate(1024, new Random(2024));
        assertEquals(1024, k.n().bitLength());
        byte[] sig = Rsa.sign(k, MSG);
        assertEquals(128, sig.length);
        assertTrue(Rsa.verify(k.publicKey(), MSG, sig));
    }

    @Test
    void signatureBytesEqualJdkAndJdkSignaturesVerify() throws Exception {
        Rsa.PrivateKey k = K512;
        KeyFactory kf = KeyFactory.getInstance("RSA");
        Signature js = Signature.getInstance("SHA256withRSA");
        js.initSign(kf.generatePrivate(new RSAPrivateCrtKeySpec(k.n(), k.e(), k.d(), k.p(), k.q(), k.dp(), k.dq(), k.qinv())));
        for (int i = 0; i < 4; i++) {
            byte[] m = ("msg " + i).getBytes(StandardCharsets.UTF_8);
            js.update(m);
            byte[] theirs = js.sign();
            byte[] ours = Rsa.sign(k, m);
            assertArrayEquals(theirs, ours, "PKCS#1 v1.5 is deterministic: bytes must match");
            assertTrue(Rsa.verify(k.publicKey(), m, theirs));
            Signature jv = Signature.getInstance("SHA256withRSA");
            jv.initVerify(kf.generatePublic(new RSAPublicKeySpec(k.n(), k.e())));
            jv.update(m);
            assertTrue(jv.verify(ours), "JDK accepts ours");
        }
    }

    @Test
    void verifyRejectsTamperingAndWrongLengths() throws Exception {
        Rsa.PublicKey pub = K512.publicKey();
        byte[] sig = Rsa.sign(K512, MSG);
        assertTrue(Rsa.verify(pub, MSG, sig), "control");
        assertFalse(Rsa.verify(pub, "attack at dusk".getBytes(StandardCharsets.UTF_8), sig));
        for (int i = 0; i < sig.length; i += 7) {
            byte[] t = sig.clone();
            t[i] ^= 0x10;
            assertFalse(Rsa.verify(pub, MSG, t), "flip byte " + i);
        }
        assertFalse(Rsa.verify(pub, MSG, Arrays.copyOf(sig, sig.length - 1)), "short");
        assertFalse(Rsa.verify(pub, MSG, Arrays.copyOf(sig, sig.length + 1)), "long");
        assertFalse(Rsa.verify(pub, MSG, new byte[0]));
        // s + n aliases the same residue; same length requires s + n < 2^512, so use s' = n - s check on a tiny key below
        BigInteger s = new BigInteger(1, sig);
        byte[] alias = Curve.i2osp(s.add(pub.n()), sig.length + 1);
        assertFalse(Rsa.verify(pub, MSG, alias), "s + n (wrong length)");
        byte[] ge = Curve.i2osp(pub.n(), sig.length);
        assertFalse(Rsa.verify(pub, MSG, ge), "s == n");
        assertFalse(Rsa.verify(pub, MSG, new byte[sig.length]), "s == 0");
    }

    @Test
    void verifyRejectsLaxPaddingForgery() throws Exception {
        byte[] hash = sha(MSG);
        byte[] prefix = Rsa.SHA256_DIGEST_INFO_PREFIX;
        int k = 64;
        // proper EM: 00 01 FF*(k-3-T) 00 T
        int tLen = prefix.length + 32;
        byte[] good = new byte[k];
        good[1] = 1;
        Arrays.fill(good, 2, k - tLen - 1, (byte) 0xff);
        System.arraycopy(prefix, 0, good, k - tLen, prefix.length);
        System.arraycopy(hash, 0, good, k - 32, 32);
        assertTrue(Rsa.verify(K512.publicKey(), MSG, rawSign(K512, good)), "control: hand-built EM is accepted");

        // Defect A: fewer FF bytes, garbage after the hash (what a lax "parse until 00" verifier accepts)
        byte[] lax = good.clone();
        Arrays.fill(lax, 2, k - tLen - 1 - 8, (byte) 0xff);
        System.arraycopy(prefix, 0, lax, k - tLen - 8, prefix.length);
        System.arraycopy(hash, 0, lax, k - 32 - 8, 32);
        lax[k - tLen - 9] = 0;
        Arrays.fill(lax, k - 8, k, (byte) 0xAA);
        assertFalse(Rsa.verify(K512.publicKey(), MSG, rawSign(K512, lax)), "garbage-suffix forgery");

        // Defect B: padding byte not FF
        byte[] badPad = good.clone();
        badPad[5] = (byte) 0xfe;
        assertFalse(Rsa.verify(K512.publicKey(), MSG, rawSign(K512, badPad)), "non-FF padding");
        // Defect C: block type 02
        byte[] type2 = good.clone();
        type2[1] = 2;
        assertFalse(Rsa.verify(K512.publicKey(), MSG, rawSign(K512, type2)), "block type");
        // Defect D: wrong digest (SHA-256 of another message)
        byte[] other = good.clone();
        System.arraycopy(sha("different".getBytes(StandardCharsets.UTF_8)), 0, other, k - 32, 32);
        assertFalse(Rsa.verify(K512.publicKey(), MSG, rawSign(K512, other)), "wrong digest");
        // Defect E: DigestInfo prefix corrupted
        byte[] oid = good.clone();
        oid[k - tLen + 10] ^= 1;
        assertFalse(Rsa.verify(K512.publicKey(), MSG, rawSign(K512, oid)), "wrong OID");
    }

    @Test
    void crtFaultIsDetectedAndNotLeaked() {
        Rsa.PrivateKey bad = Rsa.PrivateKey.unchecked(K512.p(), K512.q(), K512.e(), K512.d(),
            K512.dp(), K512.dq().add(BigInteger.TWO), K512.qinv());
        assertThrows(IllegalStateException.class, () -> Rsa.sign(bad, MSG));
        assertThrows(IllegalArgumentException.class, () -> new Rsa.PrivateKey(K512.p(), K512.q(), K512.e(), K512.d(),
            K512.dp(), K512.dq().add(BigInteger.TWO), K512.qinv()), "public constructor validates CRT parameters");
        assertThrows(IllegalArgumentException.class, () -> new Rsa.PrivateKey(K512.p(), K512.q(), K512.e(),
            K512.d().add(BigInteger.ONE), K512.dp(), K512.dq(), K512.qinv()), "d must be e^-1");
        assertNotNull(Rsa.sign(K512, MSG), "control");
    }

    @Test
    void signatureOfEmptyAndLargeMessages() throws Exception {
        for (byte[] m : new byte[][] {new byte[0], new byte[100_000]}) {
            byte[] s = Rsa.sign(K512, m);
            assertTrue(Rsa.verify(K512.publicKey(), m, s));
            Signature js = Signature.getInstance("SHA256withRSA");
            js.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(K512.n(), K512.e())));
            js.update(m);
            assertTrue(js.verify(s));
        }
    }

    @Test
    void primeSeparationGuardBoundary() {
        BigInteger p = BigInteger.ONE.shiftLeft(255).add(BigInteger.valueOf(12345));
        BigInteger gap = BigInteger.ONE.shiftLeft(256 - 100); // threshold for half = 256
        assertTrue(Rsa.wellSeparated(p.add(gap).add(BigInteger.ONE), p, 256), "gap + 1");
        assertTrue(Rsa.wellSeparated(p, p.add(gap).add(BigInteger.ONE), 256), "symmetric");
        assertFalse(Rsa.wellSeparated(p.add(gap), p, 256), "gap exactly equal is too close");
        assertFalse(Rsa.wellSeparated(p.add(gap).subtract(BigInteger.ONE), p, 256), "gap - 1");
        assertFalse(Rsa.wellSeparated(p, p, 256), "identical primes");
    }

    @Test
    void generateRejectsOddBitLengthThatWouldMissTheTarget() {
        assertThrows(IllegalArgumentException.class, () -> Rsa.generate(513, new Random(1)), "513 would give a 512-bit n");
        assertThrows(IllegalArgumentException.class, () -> Rsa.generate(1023, new Random(1)));
    }

    @Test
    void verifyRejectsSignatureWithLeadingZeroStripped() {
        // find a message whose 64-byte signature starts with 0x00 (about 1 in 3 for this key)
        for (int i = 0; i < 2000; i++) {
            byte[] m = ("strip " + i).getBytes(StandardCharsets.UTF_8);
            byte[] sig = Rsa.sign(K512, m);
            if (sig[0] == 0) {
                assertTrue(Rsa.verify(K512.publicKey(), m, sig), "control: full-length signature");
                assertFalse(Rsa.verify(K512.publicKey(), m, Arrays.copyOfRange(sig, 1, sig.length)),
                    "same integer, one byte shorter: must be rejected");
                return;
            }
        }
        fail("no signature with a leading zero byte found in 2000 tries");
    }

    @Test
    void privateKeyConstructorChecksQinv() {
        assertThrows(IllegalArgumentException.class, () -> new Rsa.PrivateKey(K512.p(), K512.q(), K512.e(), K512.d(),
            K512.dp(), K512.dq(), K512.qinv().add(BigInteger.ONE)));
        assertNotNull(new Rsa.PrivateKey(K512.p(), K512.q(), K512.e(), K512.d(), K512.dp(), K512.dq(), K512.qinv()),
            "control");
    }
}
