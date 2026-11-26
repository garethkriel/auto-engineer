package ae.utf;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Utf16Test {
    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (byte) v[i];
        }
        return r;
    }

    @Test
    void encodesSurrogatePairLiteral() {
        // U+1F600 = D83D DE00
        assertArrayEquals(b(0xD8, 0x3D, 0xDE, 0x00), Utf16.encode(new int[] {0x1F600}, true, false));
        assertArrayEquals(b(0x3D, 0xD8, 0x00, 0xDE), Utf16.encode(new int[] {0x1F600}, false, false));
        assertArrayEquals(b(0xFE, 0xFF, 0x00, 0x41), Utf16.encode(new int[] {0x41}, true, true));
        assertArrayEquals(b(0xFF, 0xFE, 0x41, 0x00), Utf16.encode(new int[] {0x41}, false, true));
    }

    @Test
    void detectsBom() {
        assertEquals(Utf16.Bom.BIG_ENDIAN, Utf16.detectBom(b(0xFE, 0xFF, 0)));
        assertEquals(Utf16.Bom.LITTLE_ENDIAN, Utf16.detectBom(b(0xFF, 0xFE)));
        assertEquals(Utf16.Bom.NONE, Utf16.detectBom(b(0xFF)));
        assertEquals(Utf16.Bom.NONE, Utf16.detectBom(b(0xEF, 0xBB, 0xBF)));
        assertEquals(Utf16.Bom.NONE, Utf16.detectBom(b()));
    }

    @Test
    void decodeKeepsBomAsFeff() {
        assertArrayEquals(new int[] {0xFEFF, 0x41}, Utf16.decode(b(0xFE, 0xFF, 0x00, 0x41), true));
        // reading big-endian bytes as little-endian shows the swapped BOM U+FFFE, a noncharacter but a scalar value
        assertArrayEquals(new int[] {0xFFFE, 0x4100}, Utf16.decode(b(0xFE, 0xFF, 0x00, 0x41), false));
    }

    @Test
    void strictErrorsCarryOffsets() {
        assertEquals(2, assertThrows(CodecException.class, () -> Utf16.decode(b(0, 0x41, 0xD8), true)).offset(),
            "odd length reports index of the dangling byte");
        assertEquals(2, assertThrows(CodecException.class, () -> Utf16.decode(b(0, 0x41, 0xD8, 0x3D), true)).offset());
        assertEquals(2, assertThrows(CodecException.class, () -> Utf16.decode(b(0, 0x41, 0xD8, 0x3D, 0, 0x41), true)).offset());
        assertEquals(2, assertThrows(CodecException.class, () -> Utf16.decode(b(0, 0x41, 0xDE, 0x00), true)).offset());
        // high surrogate followed by a unit ABOVE the low-surrogate range (E000) is an error too; control: DFFF is accepted
        assertEquals(0, assertThrows(CodecException.class, () -> Utf16.decode(b(0xD8, 0x3D, 0xE0, 0x00), true)).offset());
        assertArrayEquals(new int[] {0x10FFFF}, Utf16.decode(b(0xDB, 0xFF, 0xDF, 0xFF), true));
        assertThrows(CodecException.class, () -> Utf16.encode(new int[] {0xD800}, true, false));
        assertThrows(CodecException.class, () -> Utf16.encode(new int[] {0x110000}, true, false));
        // control: a proper pair after the same prefix decodes
        assertArrayEquals(new int[] {0x41, 0x1F600}, Utf16.decode(b(0, 0x41, 0xD8, 0x3D, 0xDE, 0x00), true));
    }

    @Test
    void randomRoundTripAgreesWithJdk() {
        Random r = new Random(5);
        for (int iter = 0; iter < 500; iter++) {
            int n = r.nextInt(30);
            int[] c = new int[n];
            for (int i = 0; i < n; i++) {
                int v = r.nextBoolean() ? r.nextInt(0xD800) : 0x10000 + r.nextInt(0x100000);
                c[i] = v;
            }
            String s = new String(c, 0, n);
            assertArrayEquals(s.getBytes(StandardCharsets.UTF_16BE), Utf16.encode(c, true, false));
            assertArrayEquals(s.getBytes(StandardCharsets.UTF_16LE), Utf16.encode(c, false, false));
            assertArrayEquals(c, Utf16.decode(Utf16.encode(c, false, false), false));
            assertArrayEquals(c, Utf16.decode(Utf16.encode(c, true, false), true));
        }
    }
}
