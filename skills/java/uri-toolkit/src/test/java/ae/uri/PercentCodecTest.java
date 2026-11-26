package ae.uri;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PercentCodecTest {
    @Test
    void encodesNonAsciiAsUppercaseUtf8() {
        assertEquals("%C3%A9a%20b", PercentCodec.encode("éa b", Chars::isUnreserved));
        assertEquals("%F0%9F%98%80", PercentCodec.encode("😀", Chars::isUnreserved));
    }

    @Test
    void keepsAllowedCharacters() {
        assertEquals("a-b._~Z9", PercentCodec.encode("a-b._~Z9", Chars::isUnreserved));
        assertEquals("a/b", PercentCodec.encode("a/b", c -> Chars.isUnreserved(c) || c == '/'));
        assertEquals("a%2Fb", PercentCodec.encode("a/b", Chars::isUnreserved));
    }

    @Test
    void decodesUtf8AndLowercaseHex() {
        assertEquals("é x", PercentCodec.decode("%c3%A9%20x"));
        assertEquals("a+b", PercentCodec.decode("a+b"));
    }

    @Test
    void decodeFormMapsPlus() {
        assertEquals("a b c", PercentCodec.decodeForm("a+b%20c"));
        assertEquals("a+b", PercentCodec.decodeForm("a%2Bb"));
    }

    @Test
    void rejectsBadTriplets() {
        assertEquals("%", PercentCodec.decode("%25"));
        assertThrows(IllegalArgumentException.class, () -> PercentCodec.decode("%"));
        assertThrows(IllegalArgumentException.class, () -> PercentCodec.decode("%2"));
        assertThrows(IllegalArgumentException.class, () -> PercentCodec.decode("%zz"));
        assertThrows(IllegalArgumentException.class, () -> PercentCodec.decode("%2g"));
    }

    @Test
    void rejectsInvalidUtf8() {
        assertEquals("é", PercentCodec.decode("%C3%A9"));
        assertThrows(IllegalArgumentException.class, () -> PercentCodec.decode("%C3"));
        assertThrows(IllegalArgumentException.class, () -> PercentCodec.decode("%C3%28"));
        assertThrows(IllegalArgumentException.class, () -> PercentCodec.decode("%FF"));
        assertThrows(IllegalArgumentException.class, () -> PercentCodec.decode("%C0%AF")); // overlong
    }

    @Test
    void roundTripsRandomStrings() {
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 500; i++) {
            StringBuilder sb = new StringBuilder();
            int n = r.nextInt(12);
            for (int j = 0; j < n; j++) {
                int k = r.nextInt(4);
                if (k == 0) sb.append((char) ('a' + r.nextInt(26)));
                else if (k == 1) sb.append(" %&=+/?#".charAt(r.nextInt(8)));
                else if (k == 2) sb.append((char) (0xE0 + r.nextInt(0x100)));
                else sb.appendCodePoint(0x1F600 + r.nextInt(20));
            }
            String s = sb.toString();
            assertEquals(s, PercentCodec.decode(PercentCodec.encode(s, Chars::isUnreserved)), s);
        }
    }

    @Test
    void lonelySurrogateIsRejectedOnEncode() {
        assertThrows(IllegalArgumentException.class, () -> PercentCodec.encode("a\uD800", Chars::isUnreserved));
    }
}
