package ae.utf;

import static org.junit.jupiter.api.Assertions.*;

import java.net.IDN;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PunycodeTest {
    private static int[] cps(String s) {
        return s.codePoints().toArray();
    }

    private static void roundTrip(String text, String puny) {
        assertEquals(puny, Punycode.encode(cps(text)), "encode " + text);
        assertArrayEquals(cps(text), Punycode.decode(puny), "decode " + puny);
    }

    @Test
    void rfc3492SampleVectors() {
        roundTrip("ليهمابتكلموشعربي؟", "egbpdaj6bu4bxfgehfvwxn");
        roundTrip("他们为什么不说中文", "ihqwcrb4cv8a8dqg056pqjye");
        roundTrip("Pročprostěnemluvíčesky", "Proprostnemluvesky-uyb24dma41a");
        roundTrip("なぜみんな日本語を話してくれないのか", "n8jok5ay5dzabd5bym9f0cm5685rrjetr6pdxa");
        roundTrip("-> $1.00 <-", "-> $1.00 <--");
    }

    @Test
    void commonLabels() {
        roundTrip("bücher", "bcher-kva");
        roundTrip("münchen", "mnchen-3ya");
        roundTrip("", "");
        roundTrip("abc", "abc-");
        roundTrip("ü", "tda");
    }

    @Test
    void decodeAcceptsUpperCaseDigits() {
        assertArrayEquals(cps("bücher"), Punycode.decode("bcher-KVA"));
    }

    @Test
    void randomLabelsRoundTripAndMatchJdkIdn() {
        String alphabet = "abcxyz09éüñøçжиあ中文한ا😀𠀀";
        int[] pool = cps(alphabet);
        Random r = new Random(3);
        for (int iter = 0; iter < 2000; iter++) {
            int n = 1 + r.nextInt(12);
            int[] c = new int[n];
            boolean nonAscii = false;
            for (int i = 0; i < n; i++) {
                c[i] = pool[r.nextInt(pool.length)];
                nonAscii |= c[i] >= 0x80;
            }
            String enc = Punycode.encode(c);
            assertArrayEquals(c, Punycode.decode(enc), enc);
            if (nonAscii) {
                String label = new String(c, 0, n);
                if (label.codePoints().anyMatch(x -> x > 0xFFFF || Character.getDirectionality(x) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC)) {
                    continue; // IDN rejects mixed-bidi labels and some astral symbols; round trip above still applies
                }
                assertEquals(IDN.toASCII(label, IDN.ALLOW_UNASSIGNED), "xn--" + enc, label);
            }
        }
    }

    @Test
    void decodeRejectsMalformedInputWithOffset() {
        assertEquals(2, assertThrows(CodecException.class, () -> Punycode.decode("a-!")).offset());
        assertEquals(0, assertThrows(CodecException.class, () -> Punycode.decode("-")).offset());
        assertEquals(1, assertThrows(CodecException.class, () -> Punycode.decode("aé-b")).offset());
        // 'b' (=1) has threshold 1 so the number continues and then runs off the end
        assertThrows(CodecException.class, () -> Punycode.decode("a-b"));
        // control with the same prefix that terminates: "a-" + digit 'a' (0 < threshold)
        assertArrayEquals(cps("\u0080a"), Punycode.decode("a-a"));
    }

    @Test
    void decodeDetectsOverflow() {
        // digit '9' has value 35, never below a threshold, so the variable-length number only grows
        assertThrows(CodecException.class, () -> Punycode.decode("9999999999999999"));
        assertThrows(CodecException.class, () -> Punycode.decode("abc-9999999999999999"));
    }

    @Test
    void decodeRejectsSurrogatesButAcceptsNeighbours() {
        // literals produced by Python's independent punycode codec for U+D800, U+DFFF, U+E000, U+10FFFF
        assertThrows(CodecException.class, () -> Punycode.decode("ib9b"));
        assertThrows(CodecException.class, () -> Punycode.decode("zy0c"));
        assertArrayEquals(new int[] {0xE000}, Punycode.decode("0y0c"));
        assertArrayEquals(new int[] {0x10FFFF}, Punycode.decode("dn32g"));
        assertEquals("dn32g", Punycode.encode(new int[] {0x10FFFF}));
    }

    @Test
    void overflowMessagesNameTheDefect() {
        CodecException e = assertThrows(CodecException.class, () -> Punycode.decode("9999999999999999"));
        assertTrue(e.getMessage().contains("overflow"), e.getMessage());
    }

    @Test
    void encodeRejectsNonScalars() {
        assertEquals(1, assertThrows(CodecException.class, () -> Punycode.encode(new int[] {0x41, 0xD800})).offset());
        assertThrows(CodecException.class, () -> Punycode.encode(new int[] {0x110000}));
    }
}
