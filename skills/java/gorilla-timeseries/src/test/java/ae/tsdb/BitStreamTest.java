package ae.tsdb;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BitStreamTest {
    @Test
    void singleBitsPackMsbFirst() {
        BitWriter w = new BitWriter();
        for (int b : new int[] {1, 0, 1, 1, 0, 0, 0, 1, 1}) w.writeBit(b);
        byte[] out = w.toByteArray();
        assertEquals(2, out.length);
        assertEquals((byte) 0b10110001, out[0]);
        assertEquals((byte) 0b10000000, out[1]);
        assertEquals(9, w.bitLength());
    }

    @Test
    void writeBitsRoundTripsAllWidths() {
        Random rnd = new Random(7);
        int[] widths = new int[200];
        long[] values = new long[200];
        BitWriter w = new BitWriter();
        for (int i = 0; i < 200; i++) {
            widths[i] = rnd.nextInt(65);
            long v = rnd.nextLong();
            values[i] = widths[i] == 64 ? v : v & ((1L << widths[i]) - 1);
            w.writeBits(values[i], widths[i]);
        }
        BitReader r = new BitReader(w.toByteArray());
        for (int i = 0; i < 200; i++) assertEquals(values[i], r.readBits(widths[i]), "item " + i);
    }

    @Test
    void sixtyFourBitExtremes() {
        BitWriter w = new BitWriter();
        w.writeBits(-1L, 64);
        w.writeBits(Long.MIN_VALUE, 64);
        w.writeBits(0, 0);
        BitReader r = new BitReader(w.toByteArray());
        assertEquals(-1L, r.readBits(64));
        assertEquals(Long.MIN_VALUE, r.readBits(64));
        assertEquals(0L, r.readBits(0));
    }

    @Test
    void readingPastEndThrows() {
        BitReader r = new BitReader(new byte[] {(byte) 0xFF});
        assertEquals(0xFF, r.readBits(8));
        assertThrows(CorruptDataException.class, r::readBit);
        assertThrows(CorruptDataException.class, () -> new BitReader(new byte[2]).readBits(17));
    }

    @Test
    void invalidWidthsRejected() {
        BitWriter w = new BitWriter();
        assertThrows(IllegalArgumentException.class, () -> w.writeBits(0, 65));
        assertThrows(IllegalArgumentException.class, () -> w.writeBits(0, -1));
        assertThrows(IllegalArgumentException.class, () -> w.writeBits(4, 2)); // value does not fit in 2 bits
    }

    @Test
    void remainingBitsCountsDownToZero() {
        BitReader r = new BitReader(new byte[] {1, 2});
        assertEquals(16, r.remainingBits());
        r.readBits(5);
        assertEquals(11, r.remainingBits());
    }
}
