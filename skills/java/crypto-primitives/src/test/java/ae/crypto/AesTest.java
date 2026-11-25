package ae.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AesTest {
    private static final String PT = "00112233445566778899aabbccddeeff";

    private static byte[] seq(int n) {
        byte[] k = new byte[n];
        for (int i = 0; i < n; i++) k[i] = (byte) i;
        return k;
    }

    @Test
    void fips197Vectors() {
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", Hex.s(enc(seq(16), Hex.b(PT))));
        assertEquals("dda97ca4864cdfe06eaf70a0ec0d7191", Hex.s(enc(seq(24), Hex.b(PT))));
        assertEquals("8ea2b7ca516745bfeafc49904b496089", Hex.s(enc(seq(32), Hex.b(PT))));
    }

    @Test
    void decryptInvertsPublishedVectors() {
        for (int[] spec : new int[][] {{16}, {24}, {32}}) {
            Aes aes = new Aes(seq(spec[0]));
            byte[] ct = enc(seq(spec[0]), Hex.b(PT));
            byte[] out = new byte[16];
            aes.decryptBlock(ct, 0, out, 0);
            assertEquals(PT, Hex.s(out), "key bytes " + spec[0]);
        }
    }

    @Test
    void fips197AppendixBKey() {
        assertEquals("3925841d02dc09fbdc118597196a0b32",
            Hex.s(enc(Hex.b("2b7e151628aed2a6abf7158809cf4f3c"), Hex.b("3243f6a8885a308d313198a2e0370734"))));
    }

    @Test
    void sboxIsComputedCorrectly() {
        // FIPS 197 Figure 7 spot values, plus structural properties.
        assertEquals(0x63, Aes.SBOX[0x00] & 0xff);
        assertEquals(0x7c, Aes.SBOX[0x01] & 0xff);
        assertEquals(0xed, Aes.SBOX[0x53] & 0xff);
        assertEquals(0x16, Aes.SBOX[0xff] & 0xff);
        boolean[] seen = new boolean[256];
        for (int i = 0; i < 256; i++) {
            int v = Aes.SBOX[i] & 0xff;
            assertFalse(seen[v], "duplicate " + v);
            seen[v] = true;
            assertEquals(i, Aes.INV_SBOX[v] & 0xff);
        }
    }

    @Test
    void invalidKeyLengthsRejected() {
        for (int n : new int[] {0, 1, 15, 17, 23, 25, 31, 33, 64}) {
            final int len = n;
            assertThrows(IllegalArgumentException.class, () -> new Aes(new byte[len]), "len " + n);
        }
        assertThrows(NullPointerException.class, () -> new Aes(null));
    }

    @Test
    void blockOffsetsAndInPlaceUse() {
        Aes aes = new Aes(seq(16));
        byte[] buf = new byte[40];
        System.arraycopy(Hex.b(PT), 0, buf, 5, 16);
        aes.encryptBlock(buf, 5, buf, 5);
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", Hex.s(Arrays.copyOfRange(buf, 5, 21)));
        assertArrayEquals(new byte[5], Arrays.copyOfRange(buf, 0, 5));
        assertArrayEquals(new byte[19], Arrays.copyOfRange(buf, 21, 40));
        aes.decryptBlock(buf, 5, buf, 5);
        assertEquals(PT, Hex.s(Arrays.copyOfRange(buf, 5, 21)));
    }

    @Test
    void keyArrayIsCopied() {
        byte[] key = seq(16);
        Aes aes = new Aes(key);
        Arrays.fill(key, (byte) 0);
        byte[] out = new byte[16];
        aes.encryptBlock(Hex.b(PT), 0, out, 0);
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", Hex.s(out));
    }

    @Test
    void randomBlocksMatchJdkEcb() throws Exception {
        Random r = new Random(7);
        for (int kl : new int[] {16, 24, 32}) {
            for (int i = 0; i < 50; i++) {
                byte[] key = new byte[kl], pt = new byte[16];
                r.nextBytes(key);
                r.nextBytes(pt);
                Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
                assertEquals(Hex.s(c.doFinal(pt)), Hex.s(enc(key, pt)));
            }
        }
    }

    @Test
    void cbcMatchesJdkForAllLengths() throws Exception {
        Random r = new Random(8);
        for (int kl : new int[] {16, 32}) {
            for (int len = 0; len <= 50; len++) {
                byte[] key = new byte[kl], iv = new byte[16], pt = new byte[len];
                r.nextBytes(key);
                r.nextBytes(iv);
                r.nextBytes(pt);
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                byte[] expected = c.doFinal(pt);
                byte[] mine = AesModes.cbcEncrypt(key, iv, pt);
                assertEquals(Hex.s(expected), Hex.s(mine), "len " + len);
                assertEquals(((len / 16) + 1) * 16, mine.length);
                assertArrayEquals(pt, AesModes.cbcDecrypt(key, iv, mine));
            }
        }
    }

    @Test
    void cbcSp80038aVector() {
        // NIST SP 800-38A F.2.1, first block only (one full block plus a padding block is produced).
        byte[] key = Hex.b("2b7e151628aed2a6abf7158809cf4f3c");
        byte[] iv = Hex.b("000102030405060708090a0b0c0d0e0f");
        byte[] ct = AesModes.cbcEncrypt(key, iv, Hex.b("6bc1bee22e409f96e93d7e117393172a"));
        assertEquals("7649abac8119b246cee98e9b12e9197d", Hex.s(Arrays.copyOf(ct, 16)));
        assertEquals(32, ct.length);
    }

    @Test
    void cbcBadPaddingTableWithControls() {
        byte[] key = seq(16), iv = new byte[16];
        Aes aes = new Aes(key);
        // Each row: last plaintext block (before encryption). Row 0 is the valid control.
        String[] blocks = {
            "0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f", // control: pad byte 15 and fifteen 0f bytes, so one plaintext byte (0x0f) remains
            "00000000000000000000000000000000", // pad byte 0
            "00000000000000000000000000000011", // pad byte 17 > 16
            "01010101010101010101010101010102", // pad byte 2 but previous byte is 01
            "10101010101010101010101010101011", // 0x11 > 16
            "ffffffffffffffffffffffffffffffff"  // 0xff
        };
        boolean[] valid = {true, false, false, false, false, false};
        for (int i = 0; i < blocks.length; i++) {
            byte[] ct = new byte[16];
            aes.encryptBlock(xor(Hex.b(blocks[i]), iv), 0, ct, 0);
            if (valid[i]) {
                assertArrayEquals(new byte[] {0x0f}, AesModes.cbcDecrypt(key, iv, ct), "row " + i);
            } else {
                CryptoException e = assertThrows(CryptoException.class, () -> AesModes.cbcDecrypt(key, iv, ct), "row " + i);
                assertEquals("bad padding", e.getMessage());
            }
        }
    }

    @Test
    void cbcRejectsLengthsWithTheSameMessage() {
        byte[] key = seq(16), iv = new byte[16];
        for (int len : new int[] {0, 1, 15, 17, 31}) {
            CryptoException e = assertThrows(CryptoException.class, () -> AesModes.cbcDecrypt(key, iv, new byte[len]));
            assertEquals("bad padding", e.getMessage(), "len " + len);
        }
    }

    @Test
    void cbcRejectsBadIvAndNulls() {
        assertThrows(IllegalArgumentException.class, () -> AesModes.cbcEncrypt(seq(16), new byte[15], new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> AesModes.cbcDecrypt(seq(16), new byte[17], new byte[16]));
        assertThrows(NullPointerException.class, () -> AesModes.cbcEncrypt(seq(16), new byte[16], null));
    }

    @Test
    void ctrMatchesJdkAndIsItsOwnInverse() throws Exception {
        Random r = new Random(9);
        for (int len : new int[] {0, 1, 15, 16, 17, 31, 32, 100, 1000}) {
            byte[] key = new byte[32], iv = new byte[16], pt = new byte[len];
            r.nextBytes(key);
            r.nextBytes(iv);
            r.nextBytes(pt);
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] ct = AesModes.ctr(key, iv, pt);
            assertEquals(Hex.s(c.doFinal(pt)), Hex.s(ct), "len " + len);
            assertArrayEquals(pt, AesModes.ctr(key, iv, ct));
        }
    }

    @Test
    void ctrCounterCarriesAcrossAllSixteenBytes() throws Exception {
        // IV ends in ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff: the third block wraps the whole 128-bit counter.
        byte[] key = seq(16), iv = new byte[16];
        Arrays.fill(iv, (byte) 0xff);
        byte[] pt = new byte[48];
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        assertEquals(Hex.s(c.doFinal(pt)), Hex.s(AesModes.ctr(key, iv, pt)));
        // Carry from byte 15 into byte 14 only.
        byte[] iv2 = new byte[16];
        iv2[15] = (byte) 0xff;
        Cipher c2 = Cipher.getInstance("AES/CTR/NoPadding");
        c2.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv2));
        assertEquals(Hex.s(c2.doFinal(pt)), Hex.s(AesModes.ctr(key, iv2, pt)));
    }

    @Test
    void ctrDoesNotMutateIv() {
        byte[] iv = new byte[16];
        iv[15] = (byte) 0xff;
        byte[] copy = iv.clone();
        AesModes.ctr(seq(16), iv, new byte[64]);
        assertArrayEquals(copy, iv);
    }

    @Test
    void gcmNistTestCases() {
        byte[] zeroKey = new byte[16], zeroIv = new byte[12];
        // Test case 1: empty plaintext
        assertEquals("58e2fccefa7e3061367f1d57a4e7455a", Hex.s(AesModes.gcmEncrypt(zeroKey, zeroIv, new byte[0], new byte[0])));
        // Test case 2: one zero block
        assertEquals("0388dace60b6a392f328c2b971b2fe78" + "ab6e47d42cec13bdf53a67b21257bddf",
            Hex.s(AesModes.gcmEncrypt(zeroKey, zeroIv, new byte[0], new byte[16])));
    }

    @Test
    void gcmNistTestCase4WithAad() {
        byte[] key = Hex.b("feffe9928665731c6d6a8f9467308308");
        byte[] iv = Hex.b("cafebabefacedbaddecaf888");
        byte[] pt = Hex.b("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"
            + "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39");
        byte[] aad = Hex.b("feedfacedeadbeeffeedfacedeadbeefabaddad2");
        assertEquals("42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e"
            + "21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091"
            + "5bc94fbc3221a5db94fae95ae7121a47", Hex.s(AesModes.gcmEncrypt(key, iv, aad, pt)));
    }

    @Test
    void gcmMatchesJdkOverLengthsAndAad() throws Exception {
        Random r = new Random(10);
        for (int kl : new int[] {16, 24, 32}) {
            for (int len : new int[] {0, 1, 15, 16, 17, 33, 100}) {
                for (int al : new int[] {0, 1, 16, 21}) {
                    byte[] key = new byte[kl], iv = new byte[12], pt = new byte[len], aad = new byte[al];
                    r.nextBytes(key);
                    r.nextBytes(iv);
                    r.nextBytes(pt);
                    r.nextBytes(aad);
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                    c.updateAAD(aad);
                    byte[] expected = c.doFinal(pt);
                    byte[] mine = AesModes.gcmEncrypt(key, iv, aad, pt);
                    assertEquals(Hex.s(expected), Hex.s(mine), "kl " + kl + " len " + len + " aad " + al);
                    assertArrayEquals(pt, AesModes.gcmDecrypt(key, iv, aad, mine));
                }
            }
        }
    }

    @Test
    void gcmRejectsEveryBitFlip() {
        byte[] key = seq(16), iv = new byte[12], aad = {1, 2, 3, 4, 5};
        byte[] pt = "attack at dawn, bring snacks".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] sealed = AesModes.gcmEncrypt(key, iv, aad, pt);
        assertArrayEquals(pt, AesModes.gcmDecrypt(key, iv, aad, sealed)); // control: untampered opens
        for (int bit = 0; bit < sealed.length * 8; bit++) {
            byte[] t = sealed.clone();
            t[bit / 8] ^= (byte) (1 << (bit % 8));
            final int b = bit;
            CryptoException e = assertThrows(CryptoException.class, () -> AesModes.gcmDecrypt(key, iv, aad, t), "bit " + b);
            assertEquals("bad tag", e.getMessage());
        }
        for (int bit = 0; bit < aad.length * 8; bit++) {
            byte[] a = aad.clone();
            a[bit / 8] ^= (byte) (1 << (bit % 8));
            assertThrows(CryptoException.class, () -> AesModes.gcmDecrypt(key, iv, a, sealed));
        }
        byte[] wrongIv = iv.clone();
        wrongIv[11] ^= 1;
        assertThrows(CryptoException.class, () -> AesModes.gcmDecrypt(key, wrongIv, aad, sealed));
    }

    @Test
    void gcmShortInputsAndBadIv() {
        byte[] key = seq(16), iv = new byte[12];
        for (int len : new int[] {0, 1, 15}) {
            CryptoException e = assertThrows(CryptoException.class, () -> AesModes.gcmDecrypt(key, iv, new byte[0], new byte[len]));
            assertEquals("bad tag", e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> AesModes.gcmEncrypt(key, new byte[16], new byte[0], new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> AesModes.gcmDecrypt(key, new byte[11], new byte[0], new byte[16]));
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] o = new byte[a.length];
        for (int i = 0; i < a.length; i++) o[i] = (byte) (a[i] ^ b[i]);
        return o;
    }

    private static byte[] enc(byte[] key, byte[] pt) {
        byte[] out = new byte[16];
        new Aes(key).encryptBlock(pt, 0, out, 0);
        return out;
    }
}
