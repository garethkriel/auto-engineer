package ae.b2t;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Bech32Test {
    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    @Test
    void validBech32Vectors() {
        String[] valid = {"A12UEL5L", "a12uel5l",
            "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
            "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
            "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
            "11" + "q".repeat(82) + "c8247j"}; // 90 chars: hrp "1", separator, 82 zero groups, checksum
        for (String s : valid) {
            Bech32.Decoded d = Bech32.decode(s);
            assertEquals(Bech32.Variant.BECH32, d.variant(), s);
            assertEquals(s.toLowerCase(Locale.ROOT), Bech32.encode(d.hrp(), d.data5(), Bech32.Variant.BECH32), s);
        }
        assertEquals("a", Bech32.decode("A12UEL5L").hrp());
        assertEquals(0, Bech32.decode("A12UEL5L").data5().length);
        assertEquals("split", Bech32.decode("split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w").hrp());
    }

    @Test
    void validBech32mVectors() {
        String[] valid = {"A1LQFN3A", "a1lqfn3a",
            "split1checkupstagehandshakeupstreamerranterredcaperredlc445v",
            "?1v759aa"};
        for (String s : valid) {
            Bech32.Decoded d = Bech32.decode(s);
            assertEquals(Bech32.Variant.BECH32M, d.variant(), s);
            assertEquals(s.toLowerCase(Locale.ROOT), Bech32.encode(d.hrp(), d.data5(), Bech32.Variant.BECH32M), s);
        }
    }

    @Test
    void invalidStrings() {
        String[] bad = {
            " 1nwldj5", "\u007f1axkwrx", "\u00801eym55h",                        // hrp char out of range
            "an84characterslonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1569pvx", // 91 chars
            "pzry9x0s0muk",                                                             // no separator
            "1pzry9x0s0muk",                                                            // empty hrp
            "x1b4n0q5v",                                                                // invalid data char
            "li1dgmt3",                                                                 // short checksum
            "de1lg7wtÿ",                                                           // invalid char in checksum
            "A1G7SGD8",                                                                 // checksum computed with uppercase hrp
            "10a06t8",                                                                  // empty hrp
            "1qzzfhee",                                                                 // empty hrp
            "a12UEL5L",                                                                 // mixed case
            "A12uel5l",                                                                 // mixed case
            "a12uel5m",                                                                 // wrong checksum
            "",                                                                         // empty
            "a1",                                                                       // no data
            "a1qqqqq"};                                                                 // 5 data chars
        for (String s : bad) {
            assertThrows(IllegalArgumentException.class, () -> Bech32.decode(s), s);
        }
    }

    @Test
    void everySingleCharacterSubstitutionBreaksChecksum() {
        String good = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
        for (int i = 3; i < good.length(); i++) {
            for (char c : CHARSET.toCharArray()) {
                if (c == good.charAt(i)) continue;
                String bad = good.substring(0, i) + c + good.substring(i + 1);
                assertThrows(IllegalArgumentException.class, () -> Bech32.decode(bad), bad);
            }
        }
    }

    @Test
    void encodeValidation() {
        assertThrows(IllegalArgumentException.class, () -> Bech32.encode("", new byte[0], Bech32.Variant.BECH32));
        assertThrows(IllegalArgumentException.class, () -> Bech32.encode("a", new byte[] {32}, Bech32.Variant.BECH32));
        assertThrows(IllegalArgumentException.class, () -> Bech32.encode("a", new byte[] {-1}, Bech32.Variant.BECH32));
        assertThrows(IllegalArgumentException.class, () -> Bech32.encode("A", new byte[0], Bech32.Variant.BECH32));
        assertThrows(IllegalArgumentException.class, () -> Bech32.encode("a b", new byte[0], Bech32.Variant.BECH32));
        assertThrows(IllegalArgumentException.class, () -> Bech32.encode("a".repeat(84), new byte[0], Bech32.Variant.BECH32));
        // exactly 90 chars: hrp 'a' + '1' + 82 data + 6 checksum
        assertEquals(90, Bech32.encode("a", new byte[82], Bech32.Variant.BECH32).length());
        assertThrows(IllegalArgumentException.class, () -> Bech32.encode("a", new byte[83], Bech32.Variant.BECH32));
    }

    @Test
    void separatorIsLastOne() {
        byte[] data = {1, 2, 3};
        String s = Bech32.encode("a1b", data, Bech32.Variant.BECH32);
        assertTrue(s.startsWith("a1b1"));
        Bech32.Decoded d = Bech32.decode(s);
        assertEquals("a1b", d.hrp());
        assertArrayEquals(data, d.data5());
    }

    @Test
    void convertBitsSemantics() {
        assertArrayEquals(new byte[] {31, 31, 31, 16}, Bech32.convertBits(new byte[] {(byte) 0xff, (byte) 0xff}, 8, 5, true));
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xff}, Bech32.convertBits(new byte[] {31, 31, 31, 16}, 5, 8, false));
        assertThrows(IllegalArgumentException.class, () -> Bech32.convertBits(new byte[] {(byte) 0xff}, 8, 5, false));
        assertThrows(IllegalArgumentException.class, () -> Bech32.convertBits(new byte[] {31, 31, 31}, 5, 8, false), "leftover 7 bits >= from");
        assertThrows(IllegalArgumentException.class, () -> Bech32.convertBits(new byte[] {32}, 5, 8, true));
        assertThrows(IllegalArgumentException.class, () -> Bech32.convertBits(new byte[] {-1}, 5, 8, true));
        // 3 zero groups = 15 bits -> 1 byte + 7 zero leftover bits (>= from): rejected even though all zero
        assertThrows(IllegalArgumentException.class, () -> Bech32.convertBits(new byte[] {0, 0, 0}, 5, 8, false));
        assertArrayEquals(new byte[] {0}, Bech32.convertBits(new byte[] {0, 0}, 5, 8, false)); // 10 bits: 1 byte + 2 zero bits
        assertArrayEquals(new byte[0], Bech32.convertBits(new byte[0], 8, 5, true));
        // 20 bytes -> 32 groups; 32 bytes -> 52 groups
        assertEquals(32, Bech32.convertBits(new byte[20], 8, 5, true).length);
        assertEquals(52, Bech32.convertBits(new byte[32], 8, 5, true).length);
        Random r = new Random(5);
        for (int i = 0; i < 1000; i++) {
            byte[] d = new byte[r.nextInt(45)];
            r.nextBytes(d);
            assertArrayEquals(d, Bech32.convertBits(Bech32.convertBits(d, 8, 5, true), 5, 8, false));
        }
    }

    @Test
    void roundTripRandom() {
        Random r = new Random(9);
        for (int i = 0; i < 2000; i++) {
            byte[] d = new byte[r.nextInt(60)];
            for (int k = 0; k < d.length; k++) d[k] = (byte) r.nextInt(32);
            String hrp = "h" + (char) ('a' + r.nextInt(26)) + (r.nextBoolean() ? "1" : "");
            for (Bech32.Variant v : Bech32.Variant.values()) {
                Bech32.Decoded dec = Bech32.decode(Bech32.encode(hrp, d, v));
                assertEquals(hrp, dec.hrp());
                assertEquals(v, dec.variant());
                assertArrayEquals(d, dec.data5());
            }
        }
    }

    @Test
    void segwitPublishedAddresses() {
        String a0 = "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4";
        Segwit.Decoded d = Segwit.decode("bc", a0);
        assertEquals(0, d.version());
        assertEquals("751e76e8199196d454941c45d1b3a323f1433bd6", HexFormat.of().formatHex(d.program()));
        assertEquals(a0.toLowerCase(Locale.ROOT), Segwit.encode("bc", 0, d.program()));
        String tap = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0";
        Segwit.Decoded t = Segwit.decode("bc", tap);
        assertEquals(1, t.version());
        assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", HexFormat.of().formatHex(t.program()));
        assertEquals(tap, Segwit.encode("bc", 1, t.program()));
        String tb = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7";
        Segwit.Decoded x = Segwit.decode("tb", tb);
        assertEquals(0, x.version());
        assertEquals(32, x.program().length);
        assertEquals(tb, Segwit.encode("tb", 0, x.program()));
    }

    @Test
    void segwitRejections() {
        assertThrows(IllegalArgumentException.class, () -> Segwit.decode("tb", "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"), "wrong hrp");
        // version 0 must be Bech32 and v1 must be Bech32m
        byte[] prog20 = new byte[20];
        byte[] data0 = concat((byte) 0, Bech32.convertBits(prog20, 8, 5, true));
        String v0m = Bech32.encode("bc", data0, Bech32.Variant.BECH32M);
        assertThrows(IllegalArgumentException.class, () -> Segwit.decode("bc", v0m));
        byte[] data1 = concat((byte) 1, Bech32.convertBits(new byte[32], 8, 5, true));
        String v1b = Bech32.encode("bc", data1, Bech32.Variant.BECH32);
        assertThrows(IllegalArgumentException.class, () -> Segwit.decode("bc", v1b));
        // v0 program length must be 20 or 32
        assertThrows(IllegalArgumentException.class, () -> Segwit.encode("bc", 0, new byte[21]));
        assertThrows(IllegalArgumentException.class, () -> Segwit.encode("bc", 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Segwit.encode("bc", 17, new byte[20]));
        assertThrows(IllegalArgumentException.class, () -> Segwit.encode("bc", -1, new byte[20]));
        assertThrows(IllegalArgumentException.class, () -> Segwit.encode("bc", 1, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> Segwit.encode("bc", 1, new byte[41]));
        assertEquals(40, Segwit.decode("bc", Segwit.encode("bc", 16, new byte[40])).program().length);
        assertEquals(2, Segwit.decode("bc", Segwit.encode("bc", 2, new byte[2])).program().length);
        // witness version 17 in data
        byte[] data17 = concat((byte) 17, Bech32.convertBits(new byte[20], 8, 5, true));
        String v17 = Bech32.encode("bc", data17, Bech32.Variant.BECH32M);
        assertThrows(IllegalArgumentException.class, () -> Segwit.decode("bc", v17));
        // non-zero padding bits: 32 bytes = 256 bits -> 52 groups = 260 bits, so 4 spare bits (a control with 0 passes below)
        byte[] g = Bech32.convertBits(new byte[32], 8, 5, true);
        assertEquals(32, Segwit.decode("bc", Bech32.encode("bc", concat((byte) 0, g), Bech32.Variant.BECH32)).program().length);
        g[g.length - 1] = 1;
        String padBad = Bech32.encode("bc", concat((byte) 0, g), Bech32.Variant.BECH32);
        assertThrows(IllegalArgumentException.class, () -> Segwit.decode("bc", padBad));
        // empty data part (no version)
        String noVer = Bech32.encode("bc", new byte[0], Bech32.Variant.BECH32);
        assertThrows(IllegalArgumentException.class, () -> Segwit.decode("bc", noVer));
    }

    private static byte[] concat(byte first, byte[] rest) {
        byte[] o = new byte[rest.length + 1];
        o[0] = first;
        System.arraycopy(rest, 0, o, 1, rest.length);
        return o;
    }
}
