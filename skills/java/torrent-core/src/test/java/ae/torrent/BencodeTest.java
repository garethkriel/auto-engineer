package ae.torrent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BencodeTest {
    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String enc(BValue v) {
        return new String(Bencode.encode(v), StandardCharsets.ISO_8859_1);
    }

    @Test
    void encodesPublishedVectors() {
        assertEquals("i42e", enc(new BInt(42)));
        assertEquals("i-7e", enc(new BInt(-7)));
        assertEquals("i0e", enc(new BInt(0)));
        assertEquals("4:spam", enc(BStr.of("spam")));
        assertEquals("0:", enc(BStr.of("")));
        assertEquals("l4:spam4:eggse", enc(new BList(List.of(BStr.of("spam"), BStr.of("eggs")))));
        // keys are emitted in sorted order regardless of insertion order
        BDict d = BDict.builder().put("spam", BStr.of("eggs")).put("cow", BStr.of("moo")).build();
        assertEquals("d3:cow3:moo4:spam4:eggse", enc(d));
    }

    @Test
    void decodesPublishedVectors() {
        assertEquals(new BInt(42), Bencode.decode(b("i42e")));
        assertEquals(new BInt(Long.MIN_VALUE), Bencode.decode(b("i-9223372036854775808e")));
        assertEquals(new BInt(Long.MAX_VALUE), Bencode.decode(b("i9223372036854775807e")));
        assertEquals(BStr.of("spam"), Bencode.decode(b("4:spam")));
        BValue v = Bencode.decode(b("d3:cow3:moo4:spam4:eggse"));
        BDict d = (BDict) v;
        assertEquals(BStr.of("moo"), d.get("cow"));
        assertEquals(BStr.of("eggs"), d.get("spam"));
        assertNull(d.get("missing"));
    }

    @Test
    void stringsAreBinarySafe() {
        byte[] raw = {0, (byte) 0xff, 'e', ':', (byte) 0x80};
        byte[] encoded = Bencode.encode(new BStr(raw));
        assertEquals(2 + raw.length, encoded.length); // "5:" + 5 bytes
        assertArrayEquals(raw, ((BStr) Bencode.decode(encoded)).bytes());
    }

    @Test
    void dictKeysOrderIsUnsignedBytes() {
        // 0x80 > 'z' when unsigned; a signed comparison would put it first.
        BStr high = new BStr(new byte[] {(byte) 0x80});
        BDict d = BDict.builder().put(high, new BInt(1)).put(BStr.of("z"), new BInt(2)).build();
        byte[] e = Bencode.encode(d);
        // "d1:z i2e 1:\x80 i1e e"
        assertEquals("d1:zi2e1:\u0080i1ee", new String(e, StandardCharsets.ISO_8859_1));
        assertEquals(d, Bencode.decode(e));
        // and the decoder rejects the signed order
        assertThrows(BencodeException.class, () -> Bencode.decode(b("d1:\u0080i1e1:zi2ee")));
    }

    /** Each rejected input is paired with a control differing only by the defect. */
    @Test
    void rejectsMalformedInputsButAcceptsControls() {
        String[][] table = {
            // {bad, control, why}
            {"i03e", "i3e", "leading zero"},
            {"i-0e", "i0e", "negative zero"},
            {"i00e", "i0e", "double zero"},
            {"ie", "i0e", "empty int"},
            {"i-e", "i-1e", "sign only"},
            {"i4", "i4e", "unterminated int"},
            {"i9223372036854775808e", "i9223372036854775807e", "overflow"},
            {"i1xe", "i1e", "non-digit"},
            {"04:spam", "4:spam", "leading zero length"},
            {"5:spam", "4:spam", "length beyond input"},
            {"4spam", "4:spam", "missing colon"},
            {"99999999999999999999:x", "1:x", "huge length"},
            {"l4:spam", "l4:spame", "unterminated list"},
            {"d3:cow3:mooi5e3:foo", "d3:cow3:moo3:fooi5ee", "unterminated dict / bad pairing"},
            {"di1e3:fooe", "d1:ai1ee", "non-string key"},
            {"d1:bi1e1:ai2ee", "d1:ai2e1:bi1ee", "unsorted keys"},
            {"d1:ai1e1:ai2ee", "d1:ai1e1:bi2ee", "duplicate keys"},
            {"d1:ai1ee3:abc", "d1:ai1ee", "trailing data"},
            {"i1ei2e", "i1e", "two values"},
            {"", "i1e", "empty input"},
            {"x", "i1e", "unknown token"},
            {"e", "le", "stray terminator"},
        };
        for (String[] row : table) {
            assertDoesNotThrow(() -> Bencode.decode(b(row[1])), "control for " + row[2]);
            assertThrows(BencodeException.class, () -> Bencode.decode(b(row[0])), row[2]);
        }
    }

    @Test
    void overlongStringIsReportedAsSuch() {
        BencodeException e = assertThrows(BencodeException.class, () -> Bencode.decode(b("5:spam")));
        assertTrue(e.getMessage().contains("longer than input"), e.getMessage());
    }

    @Test
    void nestingDepthIsLimited() {
        assertDoesNotThrow(() -> Bencode.decode(b("l".repeat(64) + "e".repeat(64))));
        assertThrows(BencodeException.class, () -> Bencode.decode(b("l".repeat(65) + "e".repeat(65))));
        // huge nesting must not overflow the stack
        assertThrows(BencodeException.class, () -> Bencode.decode(b("l".repeat(200_000))));
    }

    @Test
    void lengthPrefixIsCheckedBeforeAllocation() {
        // would be a ~2GB allocation if trusted
        assertThrows(BencodeException.class, () -> Bencode.decode(b("2000000000:abc")));
    }

    private static BValue random(Random r, int depth) {
        int kind = r.nextInt(depth <= 0 ? 2 : 4);
        switch (kind) {
            case 0:
                return new BInt(r.nextInt(5) == 0 ? r.nextLong() : r.nextInt(2001) - 1000);
            case 1: {
                byte[] x = new byte[r.nextInt(6)];
                r.nextBytes(x);
                return new BStr(x);
            }
            case 2: {
                int n = r.nextInt(4);
                java.util.ArrayList<BValue> l = new java.util.ArrayList<>();
                for (int i = 0; i < n; i++) {
                    l.add(random(r, depth - 1));
                }
                return new BList(l);
            }
            default: {
                BDict.Builder bd = BDict.builder();
                int n = r.nextInt(4);
                for (int i = 0; i < n; i++) {
                    byte[] k = new byte[1 + r.nextInt(3)];
                    r.nextBytes(k);
                    bd.put(new BStr(k), random(r, depth - 1));
                }
                return bd.build();
            }
        }
    }

    @Test
    void randomValuesRoundTripAndEncodingIsCanonical() {
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            BValue v = random(r, 4);
            byte[] e = Bencode.encode(v);
            BValue back = Bencode.decode(e);
            assertEquals(v, back);
            assertArrayEquals(e, Bencode.encode(back));
        }
    }

    @Test
    void mutatedEncodingsEitherDecodeCanonicallyOrThrowBencodeException() {
        Random r = new Random(11);
        int rejected = 0;
        int accepted = 0;
        for (int i = 0; i < 3000; i++) {
            byte[] e = Bencode.encode(random(r, 3));
            if (e.length == 0) {
                continue;
            }
            byte[] m = e.clone();
            m[r.nextInt(m.length)] = (byte) r.nextInt(256);
            try {
                BValue v = Bencode.decode(m);
                // anything accepted must be exactly canonical
                assertArrayEquals(m, Bencode.encode(v));
                accepted++;
            } catch (BencodeException ex) {
                rejected++;
            }
        }
        assertTrue(rejected > 500, "rejected " + rejected);
        assertTrue(accepted > 50, "accepted " + accepted);
    }

    @Test
    void bstrIsImmutable() {
        byte[] raw = {1, 2, 3};
        BStr s = new BStr(raw);
        raw[0] = 9;
        assertEquals(1, s.bytes()[0]);
        s.bytes()[1] = 9;
        assertEquals(2, s.bytes()[1]);
    }
}
