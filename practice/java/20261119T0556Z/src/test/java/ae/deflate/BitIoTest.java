package ae.deflate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BitIoTest {
    @Test
    void bitsArePackedLeastSignificantFirst() {
        BitWriter w = new BitWriter();
        w.bits(0b1, 1);
        w.bits(0b10, 2); // bits 1..2 = 0,1
        w.bits(0b11111, 5); // fills the byte
        assertArrayEquals(new byte[] {(byte) 0b11111_10_1}, w.toByteArray());
    }

    @Test
    void huffmanCodesAreWrittenMostSignificantBitFirst() {
        BitWriter w = new BitWriter();
        w.code(0b110, 3); // emitted as 1,1,0 -> byte bits 0..2 = 1,1,0
        assertArrayEquals(new byte[] {0b011}, w.toByteArray());
    }

    @Test
    void partialByteIsZeroPaddedAndAlignmentWorks() {
        BitWriter w = new BitWriter();
        w.bits(1, 1);
        w.alignToByte();
        w.bits(0xAB, 8);
        assertArrayEquals(new byte[] {1, (byte) 0xAB}, w.toByteArray());
    }

    @Test
    void readerRoundTripsMixedWidths() {
        BitWriter w = new BitWriter();
        int[][] fields = {{5, 3}, {1, 1}, {0x1FFF, 13}, {0, 2}, {0xFFFF, 16}, {77, 7}};
        for (int[] f : fields) {
            w.bits(f[0], f[1]);
        }
        BitReader r = new BitReader(w.toByteArray(), 0);
        for (int[] f : fields) {
            assertEquals(f[0], r.bits(f[1]));
        }
    }

    @Test
    void readingPastTheEndThrowsInflateException() {
        BitReader r = new BitReader(new byte[] {1}, 0);
        r.bits(8);
        assertThrows(InflateException.class, () -> r.bits(1));
    }

    @Test
    void alignDropsRemainingBitsAndBytePositionIsReported() {
        BitReader r = new BitReader(new byte[] {(byte) 0xFF, 0x12, 0x34}, 0);
        r.bits(3);
        r.alignToByte();
        assertEquals(1, r.bytePosition());
        assertEquals(0x12, r.bits(8));
        assertEquals(2, r.bytePosition());
    }
}
