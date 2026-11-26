package ae.cc;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CcTest {
    static final HexFormat HEX = HexFormat.of();

    static byte[] h(String s) {
        return HEX.parseHex(s.replace(" ", "").replace("\n", ""));
    }

    static byte[] rnd(Random r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    // ---------- ChaCha20 (RFC 8439 2.3.2 and 2.4.2) ----------
    @Test
    void chachaBlockVector() {
        byte[] key = h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] nonce = h("000000090000004a00000000");
        byte[] expected = h("10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e"
                + "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e");
        assertArrayEquals(expected, ChaCha20.block(key, nonce, 1));
    }

    @Test
    void chachaEncryptVector() {
        byte[] key = h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] nonce = h("000000000000004a00000000");
        byte[] pt = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip "
                + "for the future, sunscreen would be it.").getBytes(StandardCharsets.US_ASCII);
        byte[] ct = ChaCha20.xor(key, nonce, 1, pt);
        assertEquals("6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b"
                + "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8"
                + "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736"
                + "5af90bbf74a35be6b40b8eedf2785e42874d", HEX.formatHex(ct));
        assertArrayEquals(pt, ChaCha20.xor(key, nonce, 1, ct));
    }

    @Test
    void chachaCounterWrapRejected() {
        byte[] key = new byte[32], nonce = new byte[12];
        // 0xFFFFFFFF as int is -1: one block fits, a second block would wrap.
        assertEquals(64, ChaCha20.xor(key, nonce, -1, new byte[64]).length);
        assertThrows(IllegalArgumentException.class, () -> ChaCha20.xor(key, nonce, -1, new byte[65]));
    }

    @Test
    void chachaLengthChecks() {
        assertThrows(IllegalArgumentException.class, () -> ChaCha20.block(new byte[31], new byte[12], 0));
        assertThrows(IllegalArgumentException.class, () -> ChaCha20.block(new byte[32], new byte[11], 0));
        assertThrows(NullPointerException.class, () -> ChaCha20.xor(new byte[32], new byte[12], 0, null));
    }

    // ---------- Poly1305 (RFC 8439 2.5.2) ----------
    @Test
    void poly1305Vector() {
        byte[] key = h("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b");
        byte[] msg = "Cryptographic Forum Research Group".getBytes(StandardCharsets.US_ASCII);
        byte[] tag = Poly1305.mac(key, msg);
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", HEX.formatHex(tag));
        assertTrue(Poly1305.verify(key, msg, tag));
        tag[15] ^= 1;
        assertFalse(Poly1305.verify(key, msg, tag));
        assertFalse(Poly1305.verify(key, msg, new byte[15]));
    }

    @Test
    void poly1305EmptyMessageIsSPad() {
        // No blocks: the tag is just s (the second key half).
        byte[] key = new byte[32];
        for (int i = 16; i < 32; i++) key[i] = (byte) i;
        assertArrayEquals(java.util.Arrays.copyOfRange(key, 16, 32), Poly1305.mac(key, new byte[0]));
    }

    @Test
    void poly1305AccumulatorReductionEdge() {
        // Hand-derived (same shape as RFC 8439 A.3 #5): r = 2, message 0xFF*16 -> h = 2^129-1 -> tag wraps modulo 2^128.
        byte[] key = new byte[32];
        key[0] = 2;
        byte[] msg = new byte[16];
        java.util.Arrays.fill(msg, (byte) 0xff);
        assertEquals("03000000000000000000000000000000", HEX.formatHex(Poly1305.mac(key, msg)));
        // r = 2, s = 2^128-1, msg block 2 + 2^128: acc = 4 + 2^129, tag = (acc + s) mod 2^128 = 3.
        byte[] key6 = new byte[32];
        key6[0] = 2;
        java.util.Arrays.fill(key6, 16, 32, (byte) 0xff);
        byte[] m6 = new byte[16];
        m6[0] = 2;
        assertEquals("03000000000000000000000000000000", HEX.formatHex(Poly1305.mac(key6, m6)));
    }

    // ---------- AEAD (RFC 8439 2.8.2) ----------
    static final byte[] AKEY = h("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
    static final byte[] ANONCE = h("070000004041424344454647");
    static final byte[] AAD = h("50515253c0c1c2c3c4c5c6c7");
    static final byte[] APT = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip "
            + "for the future, sunscreen would be it.").getBytes(StandardCharsets.US_ASCII);

    @Test
    void aeadVector() {
        byte[] sealed = Aead.seal(AKEY, ANONCE, AAD, APT);
        assertEquals(APT.length + 16, sealed.length);
        assertEquals("1ae10b594f09e26a7e902ecbd0600691",
                HEX.formatHex(sealed, sealed.length - 16, sealed.length));
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", HEX.formatHex(sealed, 0, 16));
        assertArrayEquals(APT, Aead.open(AKEY, ANONCE, AAD, sealed).orElseThrow());
    }

    @Test
    void aeadEveryBitFlipRejectedButControlOpens() {
        byte[] sealed = Aead.seal(AKEY, ANONCE, AAD, APT);
        assertTrue(Aead.open(AKEY, ANONCE, AAD, sealed).isPresent(), "control");
        for (int i = 0; i < sealed.length; i++) {
            byte[] c = sealed.clone();
            c[i] ^= 0x01;
            assertTrue(Aead.open(AKEY, ANONCE, AAD, c).isEmpty(), "ct byte " + i);
        }
        for (int i = 0; i < AAD.length; i++) {
            byte[] a = AAD.clone();
            a[i] ^= 0x80;
            assertTrue(Aead.open(AKEY, ANONCE, a, sealed).isEmpty(), "aad byte " + i);
        }
        for (int i = 0; i < 12; i++) {
            byte[] n = ANONCE.clone();
            n[i] ^= 0x10;
            assertTrue(Aead.open(AKEY, n, AAD, sealed).isEmpty(), "nonce byte " + i);
        }
        assertTrue(Aead.open(AKEY, ANONCE, new byte[0], sealed).isEmpty(), "dropped aad");
        for (int n = 0; n < 16; n++) {
            assertEquals(Optional.empty(), Aead.open(AKEY, ANONCE, AAD, new byte[n]), "short " + n);
        }
    }

    @Test
    void aeadLengthFieldsDistinguishAadFromCiphertextSplit() {
        // Moving a byte from aad to the plaintext must change the tag even when the padded streams could agree.
        byte[] a = Aead.seal(AKEY, ANONCE, new byte[16], new byte[16]);
        byte[] b = Aead.seal(AKEY, ANONCE, new byte[0], new byte[32]);
        assertNotEquals(HEX.formatHex(a, 16, 32), HEX.formatHex(b, 32, 48));
        assertTrue(Aead.open(AKEY, ANONCE, new byte[0], a).isEmpty());
    }

    @Test
    void aeadDifferentialAgainstJdk() throws Exception {
        Random r = new Random(7);
        for (int iter = 0; iter < 250; iter++) {
            byte[] key = rnd(r, 32), nonce = rnd(r, 12);
            byte[] aad = rnd(r, r.nextInt(40));
            byte[] pt = rnd(r, iter < 70 ? iter : r.nextInt(301));
            byte[] mine = Aead.seal(key, nonce, aad, pt);
            Cipher enc = Cipher.getInstance("ChaCha20-Poly1305");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            enc.updateAAD(aad);
            assertArrayEquals(enc.doFinal(pt), mine, "seal len " + pt.length);
            Cipher dec = Cipher.getInstance("ChaCha20-Poly1305");
            dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            dec.updateAAD(aad);
            assertArrayEquals(pt, dec.doFinal(mine));
            assertArrayEquals(pt, Aead.open(key, nonce, aad, mine).orElseThrow());
        }
    }

    // ---------- X25519 (RFC 7748 5.2, 6.1) ----------
    @Test
    void x25519Vectors() {
        byte[] out = X25519.scalarMult(
                h("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                h("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"));
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", HEX.formatHex(out));
        byte[] out2 = X25519.scalarMult(
                h("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                h("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"));
        assertEquals("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957", HEX.formatHex(out2));
    }

    @Test
    void x25519DiffieHellmanVector() {
        byte[] a = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] b = h("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] aPub = X25519.publicKey(a), bPub = X25519.publicKey(b);
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", HEX.formatHex(aPub));
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", HEX.formatHex(bPub));
        String shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742";
        assertEquals(shared, HEX.formatHex(X25519.agree(a, bPub)));
        assertEquals(shared, HEX.formatHex(X25519.agree(b, aPub)));
    }

    @Test
    void x25519IgnoresTopBitOfUAndClampsScalar() {
        byte[] k = h("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
        byte[] u = h("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
        byte[] u2 = u.clone();
        u2[31] ^= (byte) 0x80;
        assertArrayEquals(X25519.scalarMult(k, u), X25519.scalarMult(k, u2));
        byte[] k2 = k.clone();
        k2[0] ^= 7; // low 3 bits are cleared by clamping
        k2[31] ^= (byte) 0x80; // top bit cleared by clamping
        assertArrayEquals(X25519.scalarMult(k, u), X25519.scalarMult(k2, u));
        byte[] k3 = k.clone();
        k3[0] ^= 8; // bit 3 is significant
        assertFalse(java.util.Arrays.equals(X25519.scalarMult(k, u), X25519.scalarMult(k3, u)));
    }

    @Test
    void x25519LowOrderPointsRejectedByAgree() {
        byte[] k = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        assertThrows(IllegalArgumentException.class, () -> X25519.agree(k, new byte[32])); // u = 0
        byte[] one = new byte[32];
        one[0] = 1; // u = 1 has order 4
        assertThrows(IllegalArgumentException.class, () -> X25519.agree(k, one));
        // control: a real key is accepted
        assertEquals(32, X25519.agree(k, X25519.publicKey(new byte[32].clone())).length);
    }

    @Test
    void x25519DifferentialAgainstJdk() throws Exception {
        Random r = new Random(11);
        KeyPairGenerator g = KeyPairGenerator.getInstance("X25519");
        for (int i = 0; i < 25; i++) {
            KeyPair mine = g.generateKeyPair();
            byte[] peerSecret = rnd(r, 32);
            byte[] peerPub = X25519.publicKey(peerSecret);
            // JDK private key bytes: use KeyFactory with XECPrivateKeySpec
            PrivateKey priv = KeyFactory.getInstance("X25519")
                    .generatePrivate(new java.security.spec.XECPrivateKeySpec(NamedParameterSpec.X25519, peerSecret));
            PublicKey jdkOfMine = mine.getPublic();
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(priv);
            ka.doPhase(jdkOfMine, true);
            byte[] jdkShared = ka.generateSecret();
            java.security.interfaces.XECPublicKey xp = (java.security.interfaces.XECPublicKey) jdkOfMine;
            byte[] u = le32(xp.getU());
            assertArrayEquals(jdkShared, X25519.agree(peerSecret, u));
            // and the JDK accepts our public key
            PublicKey ours = KeyFactory.getInstance("X25519")
                    .generatePublic(new java.security.spec.XECPublicKeySpec(
                            NamedParameterSpec.X25519, new BigInteger(1, reverse(peerPub))));
            KeyAgreement kb = KeyAgreement.getInstance("X25519");
            kb.init(mine.getPrivate());
            kb.doPhase(ours, true);
            byte[] sharedB = kb.generateSecret();
            assertEquals(32, sharedB.length);
        }
    }

    static byte[] reverse(byte[] a) {
        byte[] b = new byte[a.length];
        for (int i = 0; i < a.length; i++) b[i] = a[a.length - 1 - i];
        return b;
    }

    static byte[] le32(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    // ---------- Ed25519 (RFC 8032 7.1) ----------
    record V(String seed, String pub, String msg, String sig) {}

    static final V[] VECTORS = {
        new V("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                        + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
        new V("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
                        + "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
    };

    @Test
    void ed25519Rfc8032Vectors() {
        for (int i = 0; i < 2; i++) {
            V v = VECTORS[i];
            byte[] seed = h(v.seed), msg = h(v.msg);
            assertEquals(v.pub, HEX.formatHex(Ed25519.publicKey(seed)), "pub " + i);
            byte[] sig = Ed25519.sign(seed, msg);
            assertEquals(v.sig, HEX.formatHex(sig), "sig " + i);
            assertTrue(Ed25519.verify(h(v.pub), msg, sig), "verify " + i);
        }
    }

    @Test
    void ed25519RejectsWrongMessageKeyAndSignatureBits() {
        V v = VECTORS[1];
        byte[] pub = h(v.pub), msg = h(v.msg), sig = h(v.sig);
        assertTrue(Ed25519.verify(pub, msg, sig), "control");
        assertFalse(Ed25519.verify(pub, h("73"), sig));
        for (int i = 0; i < 64; i += 3) {
            byte[] s = sig.clone();
            s[i] ^= 0x04;
            assertFalse(Ed25519.verify(pub, msg, s), "sig byte " + i);
        }
        byte[] otherPub = Ed25519.publicKey(h(VECTORS[0].seed));
        assertFalse(Ed25519.verify(otherPub, msg, sig));
    }

    static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    @Test
    void ed25519RejectsMalleableS() {
        V v = VECTORS[0];
        byte[] pub = h(v.pub), msg = h(v.msg), sig = h(v.sig);
        BigInteger s = new BigInteger(1, reverse(java.util.Arrays.copyOfRange(sig, 32, 64)));
        assertTrue(s.compareTo(L) < 0);
        byte[] mal = sig.clone();
        byte[] sPlusL = reverse32(s.add(L));
        System.arraycopy(sPlusL, 0, mal, 32, 32);
        // s + L < 2^256, so it still fits; the cofactor-less equation holds modulo L, only strictness rejects it
        assertFalse(Ed25519.verify(pub, msg, mal), "S + L must be rejected");
        assertTrue(Ed25519.verify(pub, msg, sig), "control S");
        // S == L exactly
        byte[] eqL = sig.clone();
        System.arraycopy(reverse32(L), 0, eqL, 32, 32);
        assertFalse(Ed25519.verify(pub, msg, eqL));
        // S == L - 1 is in range (but wrong): rejected by the equation, not by the range check
        byte[] lm1 = sig.clone();
        System.arraycopy(reverse32(L.subtract(BigInteger.ONE)), 0, lm1, 32, 32);
        assertFalse(Ed25519.verify(pub, msg, lm1));
    }

    static byte[] reverse32(BigInteger v) {
        byte[] out = new byte[32];
        byte[] be = v.toByteArray();
        for (int i = 0; i < 32 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    @Test
    void ed25519RejectsNonCanonicalAndBadEncodings() {
        V v = VECTORS[0];
        byte[] pub = h(v.pub), msg = h(v.msg), sig = h(v.sig);
        // y = p (2^255 - 19) is non-canonical for A
        byte[] pNonCanon = reverse32(BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19)));
        assertFalse(Ed25519.verify(pNonCanon, msg, sig));
        // y = p + 1 (== 1, the identity) in non-canonical form
        byte[] pPlus1 = reverse32(BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(18)));
        assertFalse(Ed25519.verify(pPlus1, msg, sig));
        // R with non-canonical y
        byte[] badR = sig.clone();
        System.arraycopy(pNonCanon, 0, badR, 0, 32);
        assertFalse(Ed25519.verify(pub, msg, badR));
        // Wrong lengths never throw
        assertFalse(Ed25519.verify(new byte[31], msg, sig));
        assertFalse(Ed25519.verify(pub, msg, new byte[63]));
        assertFalse(Ed25519.verify(pub, msg, new byte[65]));
        // Y not on the curve: scan y = 2.. and require at least one rejected, control: valid pub accepted
        int rejected = 0;
        for (int y = 2; y < 40; y++) {
            byte[] a = reverse32(BigInteger.valueOf(y));
            if (!Ed25519.verify(a, msg, sig)) rejected++;
        }
        assertEquals(38, rejected);
        assertTrue(Ed25519.verify(pub, msg, sig));
    }

    // ---------- Ed25519 degenerate keys: the only way to make strictness checks observable ----------
    static final BigInteger P25519 = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));

    static byte[] sha512(byte[]... parts) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-512");
        for (byte[] p : parts) md.update(p);
        return md.digest();
    }

    /** Signature (R = [a]B, S = a mod L) under the identity public key: sB = R and k*O = O, so it verifies for ANY message. */
    @Test
    void ed25519IdentityKeyControlAndNegativeZeroRejected() throws Exception {
        byte[] seed = new byte[32];
        byte[] h = sha512(seed);
        byte[] ab = java.util.Arrays.copyOf(h, 32);
        ab[0] &= (byte) 248;
        ab[31] &= 127;
        ab[31] |= 64;
        BigInteger a = new BigInteger(1, reverse(ab)).mod(L);
        byte[] sig = new byte[64];
        System.arraycopy(Ed25519.publicKey(seed), 0, sig, 0, 32); // R = [a]B
        System.arraycopy(reverse32(a), 0, sig, 32, 32);
        byte[] identity = new byte[32];
        identity[0] = 1; // y = 1, x = 0, sign 0
        assertTrue(Ed25519.verify(identity, "any message".getBytes(StandardCharsets.UTF_8), sig), "control");
        byte[] negZero = identity.clone();
        negZero[31] |= (byte) 0x80; // x = 0 with sign bit set is invalid (RFC 8032 5.1.3 step 3)
        assertFalse(Ed25519.verify(negZero, "any message".getBytes(StandardCharsets.UTF_8), sig));
    }

    /**
     * A = (i, 0) has order 4. With S = 0 the equation reduces to R + k*A = O, i.e. R = -k*A. Choosing a message with
     * k = 1 (mod 4) makes R = -A, a point whose y is 0, which has two encodings: y = 0 and y = p.
     * Only the range checks (y >= p, S >= L) can tell the accepted control from the rejected variants.
     */
    @Test
    void ed25519TorsionTrapsExerciseRangeChecks() throws Exception {
        BigInteger i = BigInteger.TWO.modPow(P25519.subtract(BigInteger.ONE).shiftRight(2), P25519); // sqrt(-1)
        // Encodings of the order-4 points: y = 0 with x = i or -i; one has sign bit 0, the other 1.
        byte[] aEnc = new byte[32]; // y = 0, sign 0
        boolean aSignIsOdd = i.testBit(0);
        // choose A as the point whose x has sign bit 0 (so aEnc has no sign bit); then -A has the other sign bit
        // R = -A: y = 0, sign bit = 1 - signA. We compute both encodings explicitly below.
        BigInteger xA = aSignIsOdd ? P25519.subtract(i) : i; // even x => sign bit 0
        assertFalse(xA.testBit(0));
        byte[] rCanon = new byte[32];
        rCanon[31] = (byte) 0x80; // y = 0, sign 1 => x odd => -A
        byte[] rNonCanon = reverse32(P25519); // y = p (== 0 mod p), then set the sign bit
        rNonCanon[31] |= (byte) 0x80;
        byte[] msgOk = null, msgOk2 = null;
        byte[] zeroS = new byte[32];
        for (int m = 0; m < 400 && (msgOk == null || msgOk2 == null); m++) {
            byte[] msg = {(byte) m, (byte) (m >> 8)};
            if (msgOk == null && kMod4(rCanon, aEnc, msg) == 1) msgOk = msg;
            if (msgOk2 == null && kMod4(rNonCanon, aEnc, msg) == 1) msgOk2 = msg;
        }
        assertNotNull(msgOk);
        assertNotNull(msgOk2);
        // control: canonical R, S = 0
        assertTrue(Ed25519.verify(aEnc, msgOk, concat(rCanon, zeroS)), "canonical control");
        // same shape, non-canonical y = p in R: must be rejected although mod p it is the same point
        assertFalse(Ed25519.verify(aEnc, msgOk2, concat(rNonCanon, zeroS)), "y = p in R");
        // S = L is the same scalar mod L as S = 0: accepted by the equation, rejected by strictness
        assertFalse(Ed25519.verify(aEnc, msgOk, concat(rCanon, reverse32(L))), "S = L");
    }

    static int kMod4(byte[] r, byte[] a, byte[] msg) throws Exception {
        BigInteger k = new BigInteger(1, reverse(sha512(r, a, msg))).mod(L);
        return k.mod(BigInteger.valueOf(4)).intValue();
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] o = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    @Test
    void ed25519DifferentialAgainstJdk() throws Exception {
        Random r = new Random(23);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        for (int i = 0; i < 40; i++) {
            byte[] seed = rnd(r, 32);
            byte[] msg = rnd(r, i < 20 ? i * 15 : r.nextInt(301));
            PrivateKey jp = kf.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            Signature js = Signature.getInstance("Ed25519");
            js.initSign(jp);
            js.update(msg);
            byte[] jdkSig = js.sign();
            byte[] mySig = Ed25519.sign(seed, msg);
            assertArrayEquals(jdkSig, mySig, "sig " + i);
            // JDK verifies our signature under our public key
            java.security.spec.EdECPoint pt = decodePoint(Ed25519.publicKey(seed));
            PublicKey jpub = kf.generatePublic(new java.security.spec.EdECPublicKeySpec(NamedParameterSpec.ED25519, pt));
            Signature jv = Signature.getInstance("Ed25519");
            jv.initVerify(jpub);
            jv.update(msg);
            assertTrue(jv.verify(mySig));
            assertTrue(Ed25519.verify(Ed25519.publicKey(seed), msg, jdkSig));
        }
    }

    static java.security.spec.EdECPoint decodePoint(byte[] enc) {
        byte[] be = reverse(enc);
        boolean xOdd = (be[0] & 0x80) != 0;
        be[0] &= 0x7f;
        return new java.security.spec.EdECPoint(xOdd, new BigInteger(1, be));
    }

    @Test
    void ed25519SigningIsDeterministicAndSeedSensitive() {
        byte[] seed = new byte[32];
        byte[] m = {1, 2, 3};
        assertArrayEquals(Ed25519.sign(seed, m), Ed25519.sign(seed, m));
        byte[] seed2 = seed.clone();
        seed2[31] = 1;
        assertFalse(java.util.Arrays.equals(Ed25519.sign(seed, m), Ed25519.sign(seed2, m)));
        assertThrows(IllegalArgumentException.class, () -> Ed25519.sign(new byte[31], m));
    }

    @Test
    void inputsAreNotAliased() {
        byte[] key = new byte[32], nonce = new byte[12], pt = new byte[10];
        byte[] ptCopy = pt.clone();
        Aead.seal(key, nonce, new byte[0], pt);
        assertArrayEquals(ptCopy, pt);
        byte[] sealed = Aead.seal(key, nonce, new byte[0], pt);
        byte[] sealedCopy = sealed.clone();
        Aead.open(key, nonce, new byte[0], sealed);
        assertArrayEquals(sealedCopy, sealed);
    }
}
