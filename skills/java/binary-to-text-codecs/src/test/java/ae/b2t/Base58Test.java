package ae.b2t;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Base58Test {
    private static final String ALPHA = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /** Oracle: BigInteger radix conversion with explicit leading-zero handling. */
    private static String oracle(byte[] d) {
        int z = 0;
        while (z < d.length && d[z] == 0) z++;
        BigInteger n = new BigInteger(1, d);
        StringBuilder sb = new StringBuilder();
        BigInteger fifty8 = BigInteger.valueOf(58);
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(fifty8);
            sb.append(ALPHA.charAt(qr[1].intValue()));
            n = qr[0];
        }
        sb.append("1".repeat(z));
        return sb.reverse().toString();
    }

    @Test
    void knownVectors() {
        assertEquals("", Base58.encode(new byte[0]));
        assertEquals("2NEpo7TZRRrLZSi2U", Base58.encode("Hello World!".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("1", Base58.encode(new byte[] {0}));
        assertEquals("111", Base58.encode(new byte[] {0, 0, 0}));
        assertEquals("2", Base58.encode(new byte[] {1}));
        assertEquals("z", Base58.encode(new byte[] {57}));
        assertEquals("21", Base58.encode(new byte[] {58}));
        assertEquals("1112", Base58.encode(new byte[] {0, 0, 0, 1}));
        assertArrayEquals(new byte[] {0, 0, 0, 1}, Base58.decode("1112"));
        assertArrayEquals(new byte[] {0, 0}, Base58.decode("11"));
        assertArrayEquals(new byte[0], Base58.decode(""));
    }

    @Test
    void matchesBigIntegerOracleAndRoundTrips() {
        Random r = new Random(11);
        for (int i = 0; i < 3000; i++) {
            byte[] d = new byte[r.nextInt(50)];
            r.nextBytes(d);
            int lead = r.nextInt(4);
            for (int k = 0; k < lead && k < d.length; k++) d[k] = 0;
            String enc = Base58.encode(d);
            assertEquals(oracle(d), enc);
            assertArrayEquals(d, Base58.decode(enc));
        }
    }

    @Test
    void invalidCharactersRejected() {
        for (char c : "0OIl+/ \n=".toCharArray()) {
            String s = "ab" + c + "cd";
            assertThrows(IllegalArgumentException.class, () -> Base58.decode(s), s);
        }
        assertThrows(IllegalArgumentException.class, () -> Base58.decode("2é"));
    }

    @Test
    void checkKnownAddress() {
        // Version 0 with a 20-byte zero payload: published "burn" address.
        assertEquals("1111111111111111111114oLvT2", Base58Check.encode(0, new byte[20]));
        Base58Check.Decoded d = Base58Check.decode("1111111111111111111114oLvT2");
        assertEquals(0, d.version());
        assertArrayEquals(new byte[20], d.payload());
    }

    @Test
    void checkChecksumMatchesMessageDigest() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] body = HexFormat.of().parseHex("05" + "00112233445566778899aabbccddeeff00112233");
        byte[] h = sha.digest(sha.digest(body));
        byte[] full = Arrays.copyOf(body, body.length + 4);
        System.arraycopy(h, 0, full, body.length, 4);
        assertEquals(Base58.encode(full), Base58Check.encode(5, Arrays.copyOfRange(body, 1, body.length)));
    }

    @Test
    void checkRejectsEveryCharacterSubstitutionAndTruncation() {
        String good = Base58Check.encode(0x6f, HexFormat.of().parseHex("0102030405060708090a0b0c0d0e0f1011121314"));
        for (int i = 0; i < good.length(); i++) {
            for (char c : ALPHA.toCharArray()) {
                if (c == good.charAt(i)) continue;
                String bad = good.substring(0, i) + c + good.substring(i + 1);
                assertThrows(IllegalArgumentException.class, () -> Base58Check.decode(bad), bad);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Base58Check.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Base58Check.decode(Base58.encode(new byte[] {1, 2, 3, 4})));
        assertThrows(IllegalArgumentException.class, () -> Base58Check.encode(256, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> Base58Check.encode(-1, new byte[1]));
    }

    @Test
    void checkRoundTripEmptyAndLeadingZeroPayloads() {
        for (int v : new int[] {0, 1, 255}) {
            for (byte[] p : new byte[][] {new byte[0], {0}, {0, 0, 7}, {(byte) 0xff}}) {
                Base58Check.Decoded d = Base58Check.decode(Base58Check.encode(v, p));
                assertEquals(v, d.version());
                assertArrayEquals(p, d.payload());
            }
        }
    }
}
