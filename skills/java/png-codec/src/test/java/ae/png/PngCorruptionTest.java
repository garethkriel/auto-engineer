package ae.png;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** Every image here is hand-assembled with VALID CRCs, so only the named structural defect can cause rejection. */
class PngCorruptionTest {
    static byte[] ihdr(int w, int h, int depth, int type, int comp, int filt, int interlace) {
        byte[] b = new byte[13];
        PngEncoder.putInt(b, 0, w);
        PngEncoder.putInt(b, 4, h);
        b[8] = (byte) depth;
        b[9] = (byte) type;
        b[10] = (byte) comp;
        b[11] = (byte) filt;
        b[12] = (byte) interlace;
        return b;
    }

    static byte[] z(byte[] raw) {
        Deflater d = new Deflater();
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[1024];
        int n = d.deflate(buf);
        d.end();
        return java.util.Arrays.copyOf(buf, n);
    }

    /** Builds a PNG from (type, data) pairs. */
    static byte[] build(Object... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PngEncoder.SIGNATURE);
        for (int i = 0; i < chunks.length; i += 2) PngEncoder.writeChunk(out, (String) chunks[i], (byte[]) chunks[i + 1]);
        return out.toByteArray();
    }

    static final byte[] END = new byte[0];
    // 2x2 gray 8-bit, filter 0 on both rows: rows (1,2) and (3,4)
    static final byte[] RAW = {0, 1, 2, 0, 3, 4};

    static byte[] control() {
        return build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", z(RAW), "IEND", END);
    }

    static void rejects(byte[] png, String fragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PngDecoder.decode(png));
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' lacks '" + fragment + "'");
    }

    @Test
    void controlDecodesToLiteralPixels() {
        Png p = PngDecoder.decode(control());
        assertArrayEquals(new int[] {1, 2, 3, 4}, p.samples());
        assertEquals(2, p.width());
    }

    @Test
    void badSignature() {
        byte[] b = control();
        b[1] = 'X';
        rejects(b, "signature");
        rejects(new byte[3], "signature");
    }

    @Test
    void badCrcAndTruncation() {
        byte[] b = control();
        b[b.length - 1] ^= 1; // IEND crc
        rejects(b, "CRC");
        byte[] c = control();
        rejects(java.util.Arrays.copyOf(c, c.length - 3), "truncated");
    }

    @Test
    void ihdrMustBeFirstAndUnique() {
        rejects(build("IDAT", z(RAW), "IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IEND", END), "IHDR must be the first");
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", z(RAW), "IEND", END), "duplicate IHDR");
        rejects(build("IHDR", new byte[12], "IDAT", z(RAW), "IEND", END), "IHDR length");
    }

    @Test
    void illegalColourDepthPairsAndMethods() {
        rejects(build("IHDR", ihdr(2, 2, 16, 2, 0, 0, 0), "IDAT", z(RAW), "IEND", END), "colour type/bit depth");
        rejects(build("IHDR", ihdr(2, 2, 4, 6, 0, 0, 0), "IDAT", z(RAW), "IEND", END), "colour type/bit depth");
        rejects(build("IHDR", ihdr(2, 2, 8, 1, 0, 0, 0), "IDAT", z(RAW), "IEND", END), "colour type/bit depth");
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 1, 0, 0), "IDAT", z(RAW), "IEND", END), "compression");
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 1, 0), "IDAT", z(RAW), "IEND", END), "filter method");
    }

    @Test
    void interlaceIsRejectedWithItsOwnMessage() {
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 1), "IDAT", z(RAW), "IEND", END), "interlace is not supported");
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 2), "IDAT", z(RAW), "IEND", END), "unknown interlace");
    }

    @Test
    void dimensionsAreCappedBeforeAllocation() {
        rejects(build("IHDR", ihdr(0, 2, 8, 0, 0, 0, 0), "IDAT", z(RAW), "IEND", END), "dimensions");
        rejects(build("IHDR", ihdr(16385, 1, 8, 0, 0, 0, 0), "IDAT", z(RAW), "IEND", END), "dimensions");
        // 16384 x 16384 = 268M pixels would need a 268MB inflate buffer; must be refused up front
        rejects(build("IHDR", ihdr(16384, 16384, 8, 0, 0, 0, 0), "IDAT", z(RAW), "IEND", END), "dimensions");
        rejects(build("IHDR", ihdr(-1, 2, 8, 0, 0, 0, 0), "IDAT", z(RAW), "IEND", END), "dimensions"); // 0xFFFFFFFF
        // control just under the cap: 2000x2000 = 4,000,000 pixels is allowed by the cap (fails later only on data length)
        rejects(build("IHDR", ihdr(2000, 2000, 8, 0, 0, 0, 0), "IDAT", z(RAW), "IEND", END), "too short");
    }

    // palette 2x2, depth 8, indexes 0,1 / 1,0
    static final byte[] PRAW = {0, 0, 1, 0, 1, 0};
    static final byte[] PLTE2 = {1, 2, 3, 4, 5, 6};

    @Test
    void paletteControlAndDefects() {
        Png ok = PngDecoder.decode(build("IHDR", ihdr(2, 2, 8, 3, 0, 0, 0), "PLTE", PLTE2, "IDAT", z(PRAW), "IEND", END));
        assertArrayEquals(new int[] {0x010203, 0x040506}, ok.palette());
        rejects(build("IHDR", ihdr(2, 2, 8, 3, 0, 0, 0), "IDAT", z(PRAW), "IEND", END), "missing PLTE");
        rejects(build("IHDR", ihdr(2, 2, 8, 3, 0, 0, 0), "PLTE", PLTE2, "PLTE", PLTE2, "IDAT", z(PRAW), "IEND", END), "duplicate PLTE");
        rejects(build("IHDR", ihdr(2, 2, 8, 3, 0, 0, 0), "IDAT", z(PRAW), "PLTE", PLTE2, "IEND", END), "PLTE after IDAT");
        rejects(build("IHDR", ihdr(2, 2, 8, 3, 0, 0, 0), "PLTE", new byte[] {1, 2, 3, 4}, "IDAT", z(PRAW), "IEND", END), "multiple of 3");
        rejects(build("IHDR", ihdr(2, 2, 8, 3, 0, 0, 0), "PLTE", new byte[0], "IDAT", z(PRAW), "IEND", END), "multiple of 3");
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "PLTE", PLTE2, "IDAT", z(RAW), "IEND", END), "not allowed");
    }

    @Test
    void paletteSizeIsBoundedByBitDepth() {
        // depth 1 allows 2 entries; 3 entries are too many, 2 are fine (control)
        byte[] raw1 = {0, 0x40, 0, (byte) 0x80}; // 2x2 1-bit: row0 = 0,1 ; row1 = 1,0
        rejects(build("IHDR", ihdr(2, 2, 1, 3, 0, 0, 0), "PLTE", new byte[9], "IDAT", z(raw1), "IEND", END), "too many");
        Png ok = PngDecoder.decode(build("IHDR", ihdr(2, 2, 1, 3, 0, 0, 0), "PLTE", PLTE2, "IDAT", z(raw1), "IEND", END));
        assertArrayEquals(new int[] {0, 1, 1, 0}, ok.samples());
    }

    @Test
    void paletteIndexOutOfRange() {
        byte[] raw = {0, 0, 2, 0, 1, 0}; // index 2 with a 2-entry palette
        rejects(build("IHDR", ihdr(2, 2, 8, 3, 0, 0, 0), "PLTE", PLTE2, "IDAT", z(raw), "IEND", END), "out of range");
    }

    @Test
    void unusedPaddingBitsInSubByteRowsAreIgnoredButSamplesCountCorrectly() {
        // 3 wide 1-bit palette: row bytes 0b1010_1111 -> samples 1,0,1 (padding bits 1111 ignored)
        byte[] raw = {0, (byte) 0xAF};
        Png p = PngDecoder.decode(build("IHDR", ihdr(3, 1, 1, 3, 0, 0, 0), "PLTE", PLTE2, "IDAT", z(raw), "IEND", END));
        assertArrayEquals(new int[] {1, 0, 1}, p.samples());
    }

    @Test
    void idatMustBeContiguousAndPresent() {
        byte[] zz = z(RAW);
        byte[] a = java.util.Arrays.copyOfRange(zz, 0, 3);
        byte[] b = java.util.Arrays.copyOfRange(zz, 3, zz.length);
        Png split = PngDecoder.decode(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", a, "IDAT", b, "IEND", END));
        assertArrayEquals(new int[] {1, 2, 3, 4}, split.samples()); // control: contiguous split is fine
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", a, "tEXt", new byte[] {'k', 0, 'v'}, "IDAT", b, "IEND", END), "non-contiguous");
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IEND", END), "missing IDAT");
    }

    @Test
    void badFilterTypeByteNamesTheRow() {
        byte[] raw = {0, 1, 2, 7, 3, 4};
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", z(raw), "IEND", END), "filter type byte 7 in row 1");
        byte[] five = {0, 1, 2, 5, 3, 4}; // 5 is the first illegal value: the decoder's own check must name the row
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", z(five), "IEND", END), "filter type byte 5 in row 1");
        byte[] four = {0, 1, 2, 4, 3, 4}; // 4 (Paeth) is legal: control
        assertEquals(2, PngDecoder.decode(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", z(four), "IEND", END)).width());
    }

    @Test
    void inflatedSizeMustMatchExactly() {
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", z(new byte[] {0, 1, 2, 0, 3}), "IEND", END), "too short");
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", z(new byte[] {0, 1, 2, 0, 3, 4, 9}), "IEND", END), "too long");
        byte[] zz = z(RAW);
        byte[] withJunk = java.util.Arrays.copyOf(zz, zz.length + 2);
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", withJunk, "IEND", END), "trailing");
        byte[] garbage = {1, 2, 3, 4, 5};
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", garbage, "IEND", END), "zlib");
    }

    @Test
    void endOfFileRules() {
        byte[] c = control();
        byte[] trailing = java.util.Arrays.copyOf(c, c.length + 1);
        rejects(trailing, "after IEND");
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", z(RAW)), "truncated");
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "IDAT", z(RAW), "IEND", new byte[1]), "IEND must be empty");
    }

    @Test
    void ancillaryChunksAreSkippedButCriticalUnknownAreRejected() {
        Png p = PngDecoder.decode(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "tEXt", new byte[] {'a', 0, 'b'},
                "IDAT", z(RAW), "zzZz", new byte[2], "IEND", END));
        assertArrayEquals(new int[] {1, 2, 3, 4}, p.samples());
        rejects(build("IHDR", ihdr(2, 2, 8, 0, 0, 0, 0), "ABCD", new byte[0], "IDAT", z(RAW), "IEND", END), "unknown critical");
    }

    @Test
    void suggestedPaletteOnTruecolourIsIgnored() {
        byte[] raw = {0, 1, 2, 3, 4, 5, 6}; // 1x... 2x1 RGB
        Png p = PngDecoder.decode(build("IHDR", ihdr(2, 1, 8, 2, 0, 0, 0), "PLTE", PLTE2, "IDAT", z(raw), "IEND", END));
        assertNull(p.palette());
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, p.samples());
    }

    @Test
    void flippingAnyByteGivesExceptionOrIdenticalImage() {
        Png src = PngRoundTripTest.random(new Random(5), 6, 5, 3, 4);
        byte[] good = PngEncoder.encode(src, FilterMode.ADAPTIVE);
        for (int i = 0; i < good.length; i++) {
            byte[] b = good.clone();
            b[i] ^= 0x10;
            try {
                assertEquals(src, PngDecoder.decode(b), "byte " + i);
            } catch (IllegalArgumentException expected) {
                // rejected: fine
            }
        }
    }

    @Test
    void everyTruncationIsRejected() {
        byte[] good = PngEncoder.encode(PngRoundTripTest.random(new Random(6), 5, 4, 6, 8), FilterMode.FIXED_4);
        for (int n = 0; n < good.length; n++) {
            final int len = n;
            assertThrows(IllegalArgumentException.class, () -> PngDecoder.decode(java.util.Arrays.copyOf(good, len)), "len " + len);
        }
        assertNotNull(PngDecoder.decode(good));
    }

    @Test
    void pngRecordValidatesItsInputs() {
        assertThrows(IllegalArgumentException.class, () -> new Png(2, 1, 0, 8, new int[] {1}, null));
        assertThrows(IllegalArgumentException.class, () -> new Png(1, 1, 0, 1, new int[] {2}, null));
        assertThrows(IllegalArgumentException.class, () -> new Png(1, 1, 3, 1, new int[] {0}, null));
        assertThrows(IllegalArgumentException.class, () -> new Png(1, 1, 3, 1, new int[] {0}, new int[3]));
        assertThrows(IllegalArgumentException.class, () -> new Png(1, 1, 0, 8, new int[] {0}, new int[1]));
        assertThrows(IllegalArgumentException.class, () -> new Png(1, 1, 2, 16, new int[] {0, 0, 0}, null));
        int[] s = {5};
        Png p = new Png(1, 1, 0, 8, s, null);
        s[0] = 9;
        assertEquals(5, p.samples()[0]); // defensive copy
    }
}
