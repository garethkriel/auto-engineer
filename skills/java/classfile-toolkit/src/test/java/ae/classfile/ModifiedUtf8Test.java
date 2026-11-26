package ae.classfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModifiedUtf8Test {
    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (byte) v[i];
        }
        return r;
    }

    @Test
    void encodesPublishedVectors() {
        assertArrayEquals(b('a', 'b'), ModifiedUtf8.encode("ab"));
        assertArrayEquals(b(0xC0, 0x80), ModifiedUtf8.encode("\0"));
        assertArrayEquals(b(0xC3, 0xA9), ModifiedUtf8.encode("é"));
        assertArrayEquals(b(0xE2, 0x82, 0xAC), ModifiedUtf8.encode("€"));
        // U+1F600 is the surrogate pair D83D DE00; each surrogate gets its own 3-byte form.
        assertArrayEquals(b(0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80), ModifiedUtf8.encode("😀"));
    }

    @Test
    void decodesPublishedVectors() {
        assertEquals("\0", ModifiedUtf8.decode(b(0xC0, 0x80)));
        assertEquals("😀", ModifiedUtf8.decode(b(0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80)));
        assertEquals("", ModifiedUtf8.decode(b()));
        assertEquals("b", ModifiedUtf8.decode(b('a', 'b', 'c'), 1, 1));
    }

    @Test
    void randomStringsMatchWriteUtf() {
        Random rnd = new Random(42);
        for (int n = 0; n < 2000; n++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(12);
            for (int i = 0; i < len; i++) {
                int pick = rnd.nextInt(5);
                sb.append(switch (pick) {
                    case 0 -> (char) rnd.nextInt(0x80);
                    case 1 -> (char) rnd.nextInt(0x800);
                    case 2 -> (char) rnd.nextInt(0x10000);
                    case 3 -> '\0';
                    default -> (char) (0xD800 + rnd.nextInt(0x800));
                });
            }
            String s = sb.toString();
            byte[] expected = Img.Modified.encode(s);
            assertArrayEquals(expected, ModifiedUtf8.encode(s), "encode of " + s.length() + " chars #" + n);
            assertEquals(s, ModifiedUtf8.decode(expected), "decode #" + n);
        }
    }

    @Test
    void randomBytesAgreeWithReadUtfWhereBothAccept() {
        Random rnd = new Random(7);
        int accepted = 0;
        int rejected = 0;
        for (int n = 0; n < 20000; n++) {
            byte[] data = new byte[1 + rnd.nextInt(6)];
            for (int i = 0; i < data.length; i++) {
                // bias towards lead and continuation bytes so valid sequences are common
                data[i] = (byte) (rnd.nextBoolean() ? 0x80 + rnd.nextInt(0x80) : rnd.nextInt(0x80));
                if (rnd.nextInt(4) == 0) {
                    data[i] = (byte) (0xC0 + rnd.nextInt(0x30));
                }
            }
            String jdk = readUtf(data);
            String mine;
            try {
                mine = ModifiedUtf8.decode(data);
            } catch (ClassFormatException e) {
                mine = null;
            }
            if (jdk == null) {
                assertEquals(null, mine, "JDK rejects, so must we: " + java.util.HexFormat.of().formatHex(data));
                rejected++;
            } else if (mine != null) {
                assertEquals(jdk, mine);
                assertArrayEquals(data, ModifiedUtf8.encode(mine), "accepted input must be canonical");
                accepted++;
            }
        }
        assertTrue(accepted > 2000, "accepted mix " + accepted);
        assertTrue(rejected > 2000, "rejected mix " + rejected);
    }

    private static String readUtf(byte[] data) {
        byte[] framed = new byte[data.length + 2];
        framed[0] = (byte) (data.length >> 8);
        framed[1] = (byte) data.length;
        System.arraycopy(data, 0, framed, 2, data.length);
        try {
            return new DataInputStream(new ByteArrayInputStream(framed)).readUTF();
        } catch (IOException e) {
            return null;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "00",             // raw NUL is not allowed
        "61 00",          // raw NUL after text
        "F0 90 80 80",    // 4-byte form
        "FF",             // 0xFF lead
        "80",             // stray continuation
        "C3",             // truncated 2-byte
        "E2 82",          // truncated 3-byte
        "C3 29",          // bad continuation (2-byte)
        "E2 29 AC",       // bad continuation (3-byte, first)
        "E2 82 29",       // bad continuation (3-byte, second)
        "C0 81",          // overlong: C0 is only valid with 80
        "C1 81",          // overlong 'A'
        "E0 80 80",       // overlong NUL as 3 bytes
        "E0 9F BF",       // overlong U+07FF as 3 bytes
    })
    void rejectsMalformed(String hex) {
        byte[] data = java.util.HexFormat.of().parseHex(hex.replace(" ", ""));
        assertThrows(ClassFormatException.class, () -> ModifiedUtf8.decode(data), hex);
    }

    @Test
    void acceptsSmallestLegalThreeByteAndTwoByte() {
        assertEquals("\u0080", ModifiedUtf8.decode(b(0xC2, 0x80)));
        assertEquals("ࠀ", ModifiedUtf8.decode(b(0xE0, 0xA0, 0x80)));
        assertEquals("￿", ModifiedUtf8.decode(b(0xEF, 0xBF, 0xBF)));
    }
}
