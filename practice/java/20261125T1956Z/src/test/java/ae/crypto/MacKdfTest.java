package ae.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MacKdfTest {
    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test
    void rfc4231Case1() {
        byte[] key = new byte[20];
        java.util.Arrays.fill(key, (byte) 0x0b);
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", Hex.s(Hmac.sha256(key, ascii("Hi There"))));
    }

    @Test
    void rfc4231Case2() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Hex.s(Hmac.sha256(ascii("Jefe"), ascii("what do ya want for nothing?"))));
    }

    @Test
    void rfc4231Case6KeyLongerThanBlock() {
        byte[] key = new byte[131];
        java.util.Arrays.fill(key, (byte) 0xaa);
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            Hex.s(Hmac.sha256(key, ascii("Test Using Larger Than Block-Size Key - Hash Key First"))));
    }

    @Test
    void keyLengthsAroundBlockSizeMatchJdk() throws Exception {
        Random r = new Random(5);
        for (int kl : new int[] {0, 1, 31, 32, 63, 64, 65, 128, 200}) {
            byte[] key = new byte[kl];
            r.nextBytes(key);
            byte[] msg = new byte[kl * 2 + 3];
            r.nextBytes(msg);
            Mac m = Mac.getInstance("HmacSHA256");
            // JDK rejects an empty SecretKeySpec, so the empty key is checked with a zero-padded one (HMAC pads with zeros).
            byte[] jdkKey = kl == 0 ? new byte[] {0} : key;
            m.init(new SecretKeySpec(jdkKey, "HmacSHA256"));
            assertEquals(Hex.s(m.doFinal(msg)), Hex.s(Hmac.sha256(key, msg)), "key len " + kl);
        }
    }

    @Test
    void hkdfRfc5869Case1() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] prk = Kdf.hkdfExtract(Hex.b("000102030405060708090a0b0c"), ikm);
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Hex.s(prk));
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.s(Kdf.hkdfExpand(prk, Hex.b("f0f1f2f3f4f5f6f7f8f9"), 42)));
    }

    @Test
    void hkdfRfc5869Case3EmptySaltAndInfo() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] prk = Kdf.hkdfExtract(new byte[0], ikm);
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", Hex.s(prk));
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hex.s(Kdf.hkdfExpand(prk, new byte[0], 42)));
    }

    @Test
    void hkdfExpandLengthLimits() {
        byte[] prk = new byte[32];
        assertEquals(0, Kdf.hkdfExpand(prk, new byte[0], 0).length);
        assertEquals(255 * 32, Kdf.hkdfExpand(prk, new byte[0], 255 * 32).length);
        assertThrows(IllegalArgumentException.class, () -> Kdf.hkdfExpand(prk, new byte[0], 255 * 32 + 1));
        assertThrows(IllegalArgumentException.class, () -> Kdf.hkdfExpand(prk, new byte[0], -1));
    }

    @Test
    void hkdfExpandIsPrefixConsistent() {
        byte[] prk = Hmac.sha256(ascii("k"), ascii("m"));
        byte[] big = Kdf.hkdfExpand(prk, ascii("ctx"), 100);
        byte[] small = Kdf.hkdfExpand(prk, ascii("ctx"), 33);
        assertArrayEquals(java.util.Arrays.copyOf(big, 33), small);
    }

    @Test
    void pbkdf2PublishedVectors() {
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            Hex.s(Kdf.pbkdf2Sha256(ascii("password"), ascii("salt"), 1, 32)));
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            Hex.s(Kdf.pbkdf2Sha256(ascii("password"), ascii("salt"), 2, 32)));
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            Hex.s(Kdf.pbkdf2Sha256(ascii("password"), ascii("salt"), 4096, 32)));
    }

    @Test
    void pbkdf2MatchesJdkAcrossOutputLengths() throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        Random r = new Random(6);
        for (int dk : new int[] {1, 31, 32, 33, 64, 65}) {
            char[] pw = ("pw" + dk).toCharArray();
            byte[] salt = new byte[9 + dk % 5];
            r.nextBytes(salt);
            byte[] jdk = f.generateSecret(new PBEKeySpec(pw, salt, 7, dk * 8)).getEncoded();
            byte[] mine = Kdf.pbkdf2Sha256(new String(pw).getBytes(StandardCharsets.UTF_8), salt, 7, dk);
            assertEquals(Hex.s(jdk), Hex.s(mine), "dkLen " + dk);
        }
    }

    @Test
    void pbkdf2Validation() {
        assertThrows(IllegalArgumentException.class, () -> Kdf.pbkdf2Sha256(new byte[1], new byte[1], 0, 16));
        assertThrows(IllegalArgumentException.class, () -> Kdf.pbkdf2Sha256(new byte[1], new byte[1], 1, 0));
        assertThrows(NullPointerException.class, () -> Kdf.pbkdf2Sha256(null, new byte[1], 1, 16));
    }

    @Test
    void constantTimeEquals() {
        assertTrue(ConstantTime.equals(new byte[] {1, 2}, new byte[] {1, 2}));
        assertTrue(ConstantTime.equals(new byte[0], new byte[0]));
        assertFalse(ConstantTime.equals(new byte[] {1, 2}, new byte[] {1, 3}));
        assertFalse(ConstantTime.equals(new byte[] {0, 2}, new byte[] {1, 2}));
        assertFalse(ConstantTime.equals(new byte[] {1, 2}, new byte[] {1, 2, 0}));
        assertFalse(ConstantTime.equals(new byte[] {1, 2, 0}, new byte[] {1, 2}));
    }
}
