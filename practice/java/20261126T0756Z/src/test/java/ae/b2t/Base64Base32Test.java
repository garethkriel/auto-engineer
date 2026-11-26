package ae.b2t;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Base64Base32Test {
    private static byte[] b(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test
    void base64Rfc4648Vectors() {
        String[][] v = {{"", ""}, {"f", "Zg=="}, {"fo", "Zm8="}, {"foo", "Zm9v"}, {"foob", "Zm9vYg=="},
            {"fooba", "Zm9vYmE="}, {"foobar", "Zm9vYmFy"}};
        for (String[] p : v) {
            assertEquals(p[1], Base64.encode(b(p[0]), false, true));
            assertArrayEquals(b(p[0]), Base64.decode(p[1], false, true));
        }
    }

    @Test
    void base64UrlAlphabetAndUnpadded() {
        byte[] d = {(byte) 0xfb, (byte) 0xff, (byte) 0xbe};
        assertEquals("+/++", Base64.encode(d, false, true));
        assertEquals("-_--", Base64.encode(d, true, true));
        assertEquals("Zg", Base64.encode(b("f"), true, false));
        assertArrayEquals(b("f"), Base64.decode("Zg", true, false));
        assertThrows(IllegalArgumentException.class, () -> Base64.decode("+/++", true, true));
        assertThrows(IllegalArgumentException.class, () -> Base64.decode("-_--", false, true));
    }

    @Test
    void base64Strictness() {
        String[] bad = {"Zg=", "Zg", "Zg===", "Z", "Zh==", "Zm9=", "Zm 9v", "Zm9v\n", "=Zm9", "Zg==Zg==", "Zm9v=", "Zm9é"};
        for (String s : bad) {
            assertThrows(IllegalArgumentException.class, () -> Base64.decode(s, false, true), s);
        }
        assertThrows(IllegalArgumentException.class, () -> Base64.decode("Zg==", false, false), "padding rejected when unpadded");
        assertThrows(IllegalArgumentException.class, () -> Base64.decode("Zh", false, false), "non-zero trailing bits");
        assertThrows(IllegalArgumentException.class, () -> Base64.decode("Z", false, false), "length%4==1");
        assertThrows(NullPointerException.class, () -> Base64.decode(null, false, true));
    }

    @Test
    void base64MatchesJdkOnRandomData() {
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            byte[] d = new byte[r.nextInt(40)];
            r.nextBytes(d);
            assertEquals(java.util.Base64.getEncoder().encodeToString(d), Base64.encode(d, false, true));
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(d), Base64.encode(d, true, false));
            assertArrayEquals(d, Base64.decode(java.util.Base64.getUrlEncoder().encodeToString(d), true, true));
            assertArrayEquals(d, Base64.decode(Base64.encode(d, false, false), false, false));
        }
    }

    @Test
    void base64EveryNonCanonicalTailRejected() {
        // For 1 trailing byte the second char carries 4 spare bits: only indexes divisible by 16 are canonical.
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int ok = 0;
        for (int i = 0; i < 64; i++) {
            String s = "A" + alpha.charAt(i);
            try { Base64.decode(s, false, false); ok++; } catch (IllegalArgumentException e) { /* expected */ }
        }
        assertEquals(4, ok); // indexes 0,16,32,48
        ok = 0;
        for (int i = 0; i < 64; i++) {
            try { Base64.decode("AA" + alpha.charAt(i), false, false); ok++; } catch (IllegalArgumentException e) { /* expected */ }
        }
        assertEquals(16, ok); // 2 spare bits: indexes divisible by 4
    }

    @Test
    void base32Rfc4648Vectors() {
        String[][] std = {{"", ""}, {"f", "MY======"}, {"fo", "MZXQ===="}, {"foo", "MZXW6==="}, {"foob", "MZXW6YQ="},
            {"fooba", "MZXW6YTB"}, {"foobar", "MZXW6YTBOI======"}};
        String[][] hex = {{"", ""}, {"f", "CO======"}, {"fo", "CPNG===="}, {"foo", "CPNMU==="}, {"foob", "CPNMUOG="},
            {"fooba", "CPNMUOJ1"}, {"foobar", "CPNMUOJ1E8======"}};
        for (String[] p : std) {
            assertEquals(p[1], Base32.encode(b(p[0]), false, true));
            assertArrayEquals(b(p[0]), Base32.decode(p[1], false, true));
            assertEquals(p[1].replace("=", ""), Base32.encode(b(p[0]), false, false));
        }
        for (String[] p : hex) {
            assertEquals(p[1], Base32.encode(b(p[0]), true, true));
            assertArrayEquals(b(p[0]), Base32.decode(p[1], true, true));
        }
    }

    @Test
    void base32Strictness() {
        String[] bad = {"MY=====", "MY", "M", "MZXW6Y", "mzxw6ytb", "MZXW6YT1", "MY======MY======", "=Y======", "MZ======",
            "MY=A====", "MZXW6YQ", "M1======"};
        for (String s : bad) {
            assertThrows(IllegalArgumentException.class, () -> Base32.decode(s, false, true), s);
        }
        assertArrayEquals(b("f"), Base32.decode("MY", false, false)); // Y=11000: 2 spare bits are 00
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("MZ", false, false), "Z=11001: spare bits 01");
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("MZXW6YTB", true, true), "std string is not hex alphabet");
    }

    @Test
    void base32ValidUnpaddedLengthsOnly() {
        Random r = new Random(3);
        for (int n = 0; n < 30; n++) {
            byte[] d = new byte[n];
            r.nextBytes(d);
            for (boolean hex : new boolean[] {false, true}) {
                String u = Base32.encode(d, hex, false);
                assertArrayEquals(d, Base32.decode(u, hex, false));
                assertArrayEquals(d, Base32.decode(Base32.encode(d, hex, true), hex, true));
                int m = u.length() % 8;
                assertTrue(m == 0 || m == 2 || m == 4 || m == 5 || m == 7, "len " + u.length());
                // appending one more char to a valid unpadded string yields length%8 in {1,3,6} for m in {0,2,5}
                if (m == 0 || m == 2 || m == 5) {
                    String extra = u + (hex ? "0" : "A");
                    assertThrows(IllegalArgumentException.class, () -> Base32.decode(extra, hex, false));
                }
            }
        }
    }
}
