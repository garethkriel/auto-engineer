package ae.columnar;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BitPackingTest {

    @Test
    void widthForUnsignedMax() {
        assertEquals(0, BitPacking.widthFor(0));
        assertEquals(1, BitPacking.widthFor(1));
        assertEquals(2, BitPacking.widthFor(2));
        assertEquals(8, BitPacking.widthFor(255));
        assertEquals(9, BitPacking.widthFor(256));
        assertEquals(63, BitPacking.widthFor(Long.MAX_VALUE));
        assertEquals(64, BitPacking.widthFor(Long.MIN_VALUE)); // 2^63 unsigned
        assertEquals(64, BitPacking.widthFor(-1L));            // 2^64-1 unsigned
    }

    @Test
    void wordsForRoundsUp() {
        assertEquals(0, BitPacking.wordsFor(10, 0));
        assertEquals(0, BitPacking.wordsFor(0, 7));
        assertEquals(1, BitPacking.wordsFor(1, 1));
        assertEquals(1, BitPacking.wordsFor(64, 1));
        assertEquals(2, BitPacking.wordsFor(65, 1));
        assertEquals(2, BitPacking.wordsFor(10, 7));   // 70 bits
        assertEquals(9, BitPacking.wordsFor(9, 64));
    }

    @Test
    void straddlingWidthSevenLayout() {
        // width 7: value i occupies bits [7i, 7i+7). Value 9 (index 9) sits at bit 63..69, straddling words.
        long[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 127};
        long[] words = BitPacking.pack(values, 7);
        assertEquals(2, words.length);
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], BitPacking.get(words, 7, i), "index " + i);
        }
        // index 0 is the low 7 bits of word 0
        assertEquals(1L, words[0] & 0x7F);
        // index 9 = 127 starts at bit 63: its lowest bit is word0 bit 63, the rest in word1 bits 0..5
        assertEquals(1L, words[0] >>> 63);
        assertEquals(0x3FL, words[1] & 0x3F);
        assertEquals(0L, words[1] >>> 6, "padding bits must be zero");
    }

    @Test
    void roundTripEveryWidth() {
        Random rnd = new Random(7);
        for (int width = 0; width <= 64; width++) {
            long mask = width == 64 ? -1L : (1L << width) - 1;
            for (int n : new int[] {1, 2, 63, 64, 65, 200}) {
                long[] v = new long[n];
                for (int i = 0; i < n; i++) {
                    v[i] = rnd.nextLong() & mask;
                }
                if (n > 2) {
                    v[1] = mask; // maximal value for the width
                }
                long[] words = BitPacking.pack(v, width);
                assertEquals(BitPacking.wordsFor(n, width), words.length);
                for (int i = 0; i < n; i++) {
                    assertEquals(v[i], BitPacking.get(words, width, i), "w=" + width + " n=" + n + " i=" + i);
                }
            }
        }
    }

    @Test
    void packRejectsValueTooWideForWidth() {
        assertThrows(IllegalArgumentException.class, () -> BitPacking.pack(new long[] {4}, 2));
        assertThrows(IllegalArgumentException.class, () -> BitPacking.pack(new long[] {1}, 0));
        assertThrows(IllegalArgumentException.class, () -> BitPacking.pack(new long[] {-1L}, 63));
        assertThrows(IllegalArgumentException.class, () -> BitPacking.pack(new long[] {1}, 65));
        assertThrows(IllegalArgumentException.class, () -> BitPacking.pack(new long[] {1}, -1));
    }
}
