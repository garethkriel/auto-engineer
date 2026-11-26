package ae.hash;

import static ae.hash.TestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MacTest {
    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    private static byte[] jdkHmac(String alg, byte[] key, byte[] msg) throws Exception {
        Mac m = Mac.getInstance(alg);
        m.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, alg));
        return m.doFinal(msg);
    }

    @Test
    void hmacSha256Rfc4231Case2() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Hex.encode(Hmac.mac(sha256(), ascii("Jefe"), ascii("what do ya want for nothing?"))));
    }

    @Test
    void hmacSha256Rfc4231Case6LongKey() {
        byte[] key = new byte[131];
        java.util.Arrays.fill(key, (byte) 0xaa);  // 131 > 64-byte block: key is hashed first
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
                Hex.encode(Hmac.mac(sha256(), key,
                        ascii("Test Using Larger Than Block-Size Key - Hash Key First"))));
    }

    @Test
    void hmacSha3AgainstJdkAcrossKeyLengthsAroundBlockSize() throws Exception {
        Random rnd = new Random(5);
        for (int keyLen : new int[] {1, 31, 135, 136, 137, 200}) {
            byte[] key = new byte[keyLen];
            rnd.nextBytes(key);
            byte[] msg = new byte[rnd.nextInt(400)];
            rnd.nextBytes(msg);
            assertArrayEquals(jdkHmac("HmacSHA3-256", key, msg), Hmac.mac(Keccak::sha3_256, key, msg), "keyLen=" + keyLen);
        }
    }

    @Test
    void hmacSha3_512LongKeyLiteralAndBlake2Literal() {
        byte[] key = new byte[200];
        for (int i = 0; i < 200; i++) {
            key[i] = (byte) i;
        }
        assertEquals("acf21f79a9f78b9b92d9bd0696004a1704ee791e7f4ef54f043e2dba9b179508bd7a16a968ac7adb9df969da5e1abd981f39e0254d6e07dc293f7c7fb189d8ae",
                Hex.encode(Hmac.mac(Keccak::sha3_512, key, ascii("hello"))));
        byte[] k = new byte[200];
        java.util.Arrays.fill(k, (byte) 'k');
        assertEquals("98de2352941a17672c6e5b8cb66ea726051e57b595399111f3a2baf3cb29ce1a78de179931c4d66f03fe4738c74bd6bd8d9ea717de572c6afd2cfa43f936eb82",
                Hex.encode(Hmac.mac(Blake2b::new, k, ascii("msg"))));
    }

    @Test
    void incrementalHmacMatchesOneShotAndResetsKeepingKey() {
        Hmac h = new Hmac(Keccak::sha3_256, ascii("key"));
        h.update(ascii("what "));
        h.update(ascii("is this"));
        byte[] first = h.doFinal();
        assertArrayEquals(Hmac.mac(Keccak::sha3_256, ascii("key"), ascii("what is this")), first);
        h.update(ascii("what is this"));
        assertArrayEquals(first, h.doFinal(), "doFinal must reset to the keyed initial state");
    }

    @Test
    void hmacEmptyKeyAndEmptyMessage() throws Exception {
        assertArrayEquals(jdkHmac("HmacSHA256", new byte[0], new byte[0]),
                Hmac.mac(sha256(), new byte[0], new byte[0]),
                "an empty key is the all-zero block");
        assertThrows(NullPointerException.class, () -> Hmac.mac(sha256(), null, new byte[0]));
    }

    @Test
    void hkdfRfc5869Case1() {
        byte[] ikm = Hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = Hex.decode("000102030405060708090a0b0c");
        byte[] info = Hex.decode("f0f1f2f3f4f5f6f7f8f9");
        byte[] prk = Hkdf.extract(sha256(), salt, ikm);
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Hex.encode(prk));
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                Hex.encode(Hkdf.expand(sha256(), prk, info, 42)));
    }

    @Test
    void hkdfRfc5869Case3EmptySaltAndInfo() {
        byte[] ikm = Hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] prk = Hkdf.extract(sha256(), new byte[0], ikm);
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", Hex.encode(prk));
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                Hex.encode(Hkdf.expand(sha256(), prk, new byte[0], 42)));
        assertArrayEquals(prk, Hkdf.extract(sha256(), null, ikm), "null salt == empty salt");
    }

    @Test
    void hkdfLengthLimits() {
        byte[] prk = new byte[32];
        assertEquals(255 * 32, Hkdf.expand(sha256(), prk, new byte[0], 255 * 32).length);
        assertThrows(IllegalArgumentException.class, () -> Hkdf.expand(sha256(), prk, new byte[0], 255 * 32 + 1));
        assertThrows(IllegalArgumentException.class, () -> Hkdf.expand(sha256(), prk, new byte[0], -1));
        assertEquals(0, Hkdf.expand(sha256(), prk, new byte[0], 0).length);
    }

    @Test
    void hkdfExpandBlocksUseCounterAfterInfo() {
        // Second block = HMAC(prk, T1 || info || 0x02): rebuild it from Hmac directly.
        byte[] prk = Hex.decode("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        byte[] info = ascii("ctx");
        byte[] out = Hkdf.expand(sha256(), prk, info, 64);
        byte[] t1 = java.util.Arrays.copyOf(out, 32);
        Hmac h = new Hmac(sha256(), prk);
        h.update(t1);
        h.update(info);
        h.update(new byte[] {2});
        assertArrayEquals(h.doFinal(), java.util.Arrays.copyOfRange(out, 32, 64));
    }

    @Test
    void pbkdf2Rfc7914Vector() {
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
                Hex.encode(Pbkdf2.derive(sha256(), ascii("passwd"), ascii("salt"), 1, 64)));
    }

    @Test
    void pbkdf2AgainstJdk() throws Exception {
        Random rnd = new Random(11);
        for (int iter = 0; iter < 12; iter++) {
            char[] pw = ("pw" + rnd.nextInt(1000000)).toCharArray();
            byte[] salt = new byte[1 + rnd.nextInt(40)];
            rnd.nextBytes(salt);
            int c = 1 + rnd.nextInt(300);
            int dk = 1 + rnd.nextInt(100);  // spans several blocks of 32 bytes
            byte[] jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pw, salt, c, dk * 8)).getEncoded();
            assertArrayEquals(jdk, Pbkdf2.derive(sha256(), new String(pw).getBytes(StandardCharsets.UTF_8), salt, c, dk),
                    "iter=" + iter + " c=" + c + " dk=" + dk);
        }
    }

    @Test
    void pbkdf2BlockIndexUsesAllFourCounterBytes() throws Exception {
        // 8300 bytes = 260 SHA-256 blocks, so block indexes 256..260 need the second counter byte.
        byte[] jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec("pw".toCharArray(), ascii("salt"), 1, 8300 * 8)).getEncoded();
        assertArrayEquals(jdk, Pbkdf2.derive(sha256(), ascii("pw"), ascii("salt"), 1, 8300));
    }

    @Test
    void pbkdf2WorksOverSha3AndBlake() throws Exception {
        byte[] a = Pbkdf2.derive(Keccak::sha3_256, ascii("pw"), ascii("salt"), 3, 70);
        byte[] b = Pbkdf2.derive(Blake2b::new, ascii("pw"), ascii("salt"), 3, 70);
        assertEquals(70, a.length);
        assertNotEquals(Hex.encode(a), Hex.encode(b));
        // First block with c=1 is exactly HMAC(pw, salt || 00000001).
        byte[] one = Pbkdf2.derive(Keccak::sha3_256, ascii("pw"), ascii("salt"), 1, 32);
        byte[] saltBlock = new byte[] {'s', 'a', 'l', 't', 0, 0, 0, 1};
        assertArrayEquals(jdkHmac("HmacSHA3-256", ascii("pw"), saltBlock), one);
    }

    @Test
    void pbkdf2Validation() {
        assertThrows(IllegalArgumentException.class, () -> Pbkdf2.derive(sha256(), new byte[1], new byte[1], 0, 16));
        assertThrows(IllegalArgumentException.class, () -> Pbkdf2.derive(sha256(), new byte[1], new byte[1], 1, 0));
        assertThrows(NullPointerException.class, () -> Pbkdf2.derive(sha256(), null, new byte[1], 1, 16));
    }

    @Test
    void hexStrictness() {
        assertEquals("00ff7f", Hex.encode(new byte[] {0, -1, 127}));
        assertArrayEquals(new byte[] {0, -1, 127}, Hex.decode("00FF7f"));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("abc"));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("zz"));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("0g"));
        assertEquals(0, Hex.decode("").length);
    }
}
