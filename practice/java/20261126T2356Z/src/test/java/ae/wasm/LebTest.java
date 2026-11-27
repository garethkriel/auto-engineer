package ae.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LebTest {
    private static Reader rd(int... b) {
        byte[] x = Bin.bytes(b);
        return new Reader(x, 0, x.length);
    }

    @Test
    void unsignedLiterals() {
        assertEquals(624485L, rd(0xE5, 0x8E, 0x26).leb(32, false)); // Wikipedia example
        assertEquals(0L, rd(0x00).leb(32, false));
        assertEquals(127L, rd(0x7F).leb(32, false));
        assertEquals(128L, rd(0x80, 0x01).leb(32, false));
        assertEquals(4294967295L, rd(0xFF, 0xFF, 0xFF, 0xFF, 0x0F).leb(32, false));
    }

    @Test
    void signedLiterals() {
        assertEquals(-123456L, rd(0xC0, 0xBB, 0x78).leb(32, true)); // Wikipedia example
        assertEquals(-1L, rd(0x7F).leb(32, true));
        assertEquals(63L, rd(0x3F).leb(32, true));
        assertEquals(-64L, rd(0x40).leb(32, true));
        assertEquals(64L, rd(0xC0, 0x00).leb(32, true));
        assertEquals(Integer.MIN_VALUE, rd(0x80, 0x80, 0x80, 0x80, 0x78).leb(32, true));
        assertEquals(Integer.MAX_VALUE, rd(0xFF, 0xFF, 0xFF, 0xFF, 0x07).leb(32, true));
        assertEquals(Long.MIN_VALUE,
                rd(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x7F).leb(64, true));
        assertEquals(Long.MAX_VALUE,
                rd(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00).leb(64, true));
    }

    @Test
    void redundantPaddingWithinByteLimitIsAccepted() {
        // 0 encoded in 5 bytes is legal for u32 (the limit is the byte count, not minimality)
        assertEquals(0L, rd(0x80, 0x80, 0x80, 0x80, 0x00).leb(32, false));
        assertEquals(-1L, rd(0xFF, 0xFF, 0xFF, 0xFF, 0x7F).leb(32, true));
    }

    @Test
    void tooLongOrBadUnusedBitsRejected() {
        // sixth byte
        assertThrows(ModuleException.class, () -> rd(0x80, 0x80, 0x80, 0x80, 0x80, 0x00).leb(32, false));
        // fifth byte has bit 4 set (value >= 2^32)
        assertThrows(ModuleException.class, () -> rd(0xFF, 0xFF, 0xFF, 0xFF, 0x10).leb(32, false));
        // signed: fifth byte 0x70 has unused bits set but sign bit (bit 3) clear
        assertThrows(ModuleException.class, () -> rd(0x80, 0x80, 0x80, 0x80, 0x70).leb(32, true));
        // signed: sign bit set but unused bits not all ones
        assertThrows(ModuleException.class, () -> rd(0x80, 0x80, 0x80, 0x80, 0x48).leb(32, true));
        // s64: last byte must be 0x00 or 0x7F
        assertThrows(ModuleException.class,
                () -> rd(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01).leb(64, true));
        // continuation bit still set on the last permitted byte
        assertThrows(ModuleException.class, () -> rd(0xFF, 0xFF, 0xFF, 0xFF, 0x8F, 0x00).leb(32, false));
    }

    @Test
    void truncatedReportsOffset() {
        ModuleException e = assertThrows(ModuleException.class, () -> rd(0x80, 0x80).leb(32, false));
        assertEquals(ModuleException.Kind.MALFORMED, e.kind());
        assertTrue(e.getMessage().startsWith("malformed at offset 2: "), e.getMessage());
    }

    @Test
    void roundTripWithTestEncoder() {
        long[] vals = {0, 1, 63, 64, 127, 128, 8191, 8192, 1L << 31, (1L << 32) - 1};
        for (long v : vals) {
            byte[] enc = Bin.u(v);
            assertEquals(v, new Reader(enc, 0, enc.length).leb(32, false), "u " + v);
        }
        long[] svals = {0, 1, -1, 63, 64, -64, -65, Integer.MIN_VALUE, Integer.MAX_VALUE, 123456789, -987654321};
        for (long v : svals) {
            byte[] enc = Bin.s(v);
            assertEquals(v, new Reader(enc, 0, enc.length).leb(32, true), "s " + v);
        }
        long[] lvals = {Long.MIN_VALUE, Long.MAX_VALUE, -1L << 40, 1L << 40, 0x123456789ABCDEFL};
        for (long v : lvals) {
            byte[] enc = Bin.s(v);
            assertEquals(v, new Reader(enc, 0, enc.length).leb(64, true), "l " + v);
        }
    }

    @Test
    void s33BlockTypeBytes() {
        assertEquals(-64L, rd(0x40).leb(33, true));
        assertEquals(-1L, rd(0x7F).leb(33, true));
        assertEquals(-2L, rd(0x7E).leb(33, true));
        // s33 spans -2^32 .. 2^32-1: the 5th byte carries bits 28..32 with bit 32 (byte bit 4) as the sign
        assertEquals(-(1L << 32), rd(0x80, 0x80, 0x80, 0x80, 0x70).leb(33, true));
        assertEquals(0xF0000000L, rd(0x80, 0x80, 0x80, 0x80, 0x0F).leb(33, true));
        // 0x10 would mean sign bit set with bits 5-6 clear: not a sign extension
        assertThrows(ModuleException.class, () -> rd(0x80, 0x80, 0x80, 0x80, 0x10).leb(33, true));
    }

    @Test
    void vecCountIsCheckedAgainstRemainingBytesBeforeAllocation() {
        Reader r = rd(0xFF, 0xFF, 0xFF, 0xFF, 0x0F, 0x01);
        ModuleException e = assertThrows(ModuleException.class, r::vecCount);
        assertTrue(e.getMessage().contains("count exceeds remaining"), e.getMessage());
        Reader ok = rd(0x02, 0xAA, 0xBB);
        assertEquals(2, ok.vecCount());
        // exactly one more than the bytes that remain after the count byte
        assertThrows(ModuleException.class, () -> rd(0x03, 0xAA, 0xBB).vecCount());
        assertEquals(0, rd(0x00).vecCount());
    }
}
