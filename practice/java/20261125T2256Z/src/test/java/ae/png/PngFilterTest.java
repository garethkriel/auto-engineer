package ae.png;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PngFilterTest {
    private static final byte[] ROW = {10, 20, 30, 40};
    private static final byte[] PRIOR = {1, 2, 3, 4};
    private static final byte[] ZERO = new byte[4];

    @Test
    void noneIsIdentity() {
        assertArrayEquals(ROW, PngFilter.filter(0, ROW, PRIOR, 1));
    }

    @Test
    void subLiteral() {
        // x0: 10-0, x1: 20-10, x2: 30-20, x3: 40-30
        assertArrayEquals(new byte[] {10, 10, 10, 10}, PngFilter.filter(1, ROW, PRIOR, 1));
    }

    @Test
    void subUsesBytesPerPixelDistance() {
        // bpp 2: x2 - x0 = 30-10, x3 - x1 = 40-20
        assertArrayEquals(new byte[] {10, 20, 20, 20}, PngFilter.filter(1, ROW, PRIOR, 2));
    }

    @Test
    void upLiteral() {
        assertArrayEquals(new byte[] {9, 18, 27, 36}, PngFilter.filter(2, ROW, PRIOR, 1));
    }

    @Test
    void averageLiteral() {
        // x0: 10-floor((0+1)/2)=10; x1: 20-floor((10+2)/2)=14; x2: 30-floor((20+3)/2)=19; x3: 40-floor((30+4)/2)=23
        assertArrayEquals(new byte[] {10, 14, 19, 23}, PngFilter.filter(3, ROW, PRIOR, 1));
    }

    @Test
    void paethLiteral() {
        // x0 predictor b=1 -> 9; x1 a=10 -> 10; x2 a=20 -> 10; x3 a=30 -> 10
        assertArrayEquals(new byte[] {9, 10, 10, 10}, PngFilter.filter(4, ROW, PRIOR, 1));
    }

    @Test
    void paethPredictorTieBreakOrderIsAThenBThenC() {
        assertEquals(3, PngFilter.paeth(3, 0, 1)); // pa=1 pb=2 pc=1 -> a wins the pa==pc tie
        assertEquals(3, PngFilter.paeth(0, 3, 1)); // pa=2 pb=1 pc=1 -> b wins the pb==pc tie
        assertEquals(3, PngFilter.paeth(2, 4, 3)); // pa=1 pb=1 pc=0 -> c
        assertEquals(7, PngFilter.paeth(7, 7, 7));
        assertEquals(0, PngFilter.paeth(0, 0, 0));
    }

    @Test
    void paethHandlesFullByteRange() {
        assertEquals(0, PngFilter.paeth(255, 0, 255)); // p=0: pa=255 pb=0 pc=255 -> b
    }

    @Test
    void firstRowUsesZeroPrior() {
        assertArrayEquals(new byte[] {10, 20, 30, 40}, PngFilter.filter(2, ROW, ZERO, 1));
    }

    @Test
    void wrapsModulo256() {
        byte[] row = {(byte) 5};
        byte[] prior = {(byte) 250};
        assertArrayEquals(new byte[] {(byte) 11}, PngFilter.filter(2, row, prior, 1)); // 5-250 = -245 = 11 mod 256
        assertArrayEquals(row, PngFilter.unfilter(2, new byte[] {11}, prior, 1));
    }

    @Test
    void unfilterInvertsFilterForAllTypesAndBpps() {
        Random rnd = new Random(7);
        for (int iter = 0; iter < 300; iter++) {
            int bpp = 1 + rnd.nextInt(4);
            int len = bpp * (1 + rnd.nextInt(8));
            byte[] row = new byte[len];
            byte[] prior = new byte[len];
            rnd.nextBytes(row);
            if (rnd.nextBoolean()) rnd.nextBytes(prior);
            for (int t = 0; t <= 4; t++) {
                byte[] f = PngFilter.filter(t, row, prior, bpp);
                assertArrayEquals(row, PngFilter.unfilter(t, f, prior, bpp), "type " + t + " bpp " + bpp);
            }
        }
    }

    @Test
    void filtersDoNotMutateInputs() {
        byte[] row = ROW.clone();
        byte[] prior = PRIOR.clone();
        for (int t = 0; t <= 4; t++) {
            PngFilter.filter(t, row, prior, 1);
            PngFilter.unfilter(t, row, prior, 1);
        }
        assertArrayEquals(ROW, row);
        assertArrayEquals(PRIOR, prior);
    }

    @Test
    void rejectsBadFilterType() {
        assertThrows(IllegalArgumentException.class, () -> PngFilter.filter(5, ROW, PRIOR, 1));
        assertThrows(IllegalArgumentException.class, () -> PngFilter.unfilter(-1, ROW, PRIOR, 1));
    }
}
