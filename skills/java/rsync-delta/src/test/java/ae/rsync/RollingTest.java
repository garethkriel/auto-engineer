package ae.rsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RollingTest {
    @Test
    void knownValueForSmallWindow() {
        // bytes 1,2,3: a = 1+2+3 = 6; b = 3*1 + 2*2 + 1*3 = 10; weak = 6 | 10<<16
        byte[] d = {1, 2, 3};
        assertEquals(6 | (10 << 16), Rolling.weak(d, 0, 3));
    }

    @Test
    void bytesAreUnsigned() {
        // 0xFF must count as 255, not -1: a = 255, b = 255
        byte[] d = {(byte) 0xFF};
        assertEquals(255 | (255 << 16), Rolling.weak(d, 0, 1));
    }

    @Test
    void sumsWrapAtSixteenBits() {
        byte[] d = TestData.random(1, 5000);
        int w = Rolling.weak(d, 0, d.length);
        long a = 0;
        long b = 0;
        for (int i = 0; i < d.length; i++) {
            a += d[i] & 0xFF;
            b += (long) (d.length - i) * (d[i] & 0xFF);
        }
        assertEquals((int) (a & 0xFFFF), w & 0xFFFF);
        assertEquals((int) (b & 0xFFFF), w >>> 16);
    }

    @Test
    void rollingEqualsRecomputingAtEveryPosition() {
        for (int window : new int[] {1, 2, 3, 7, 64, 700}) {
            byte[] d = TestData.random(window, 3000);
            Rolling r = Rolling.of(d, 0, window);
            for (int pos = 0; pos + window < d.length; pos++) {
                assertEquals(Rolling.weak(d, pos, window), r.value(), "window " + window + " pos " + pos);
                r.roll(d[pos], d[pos + window]);
            }
            assertEquals(Rolling.weak(d, d.length - window, window), r.value());
        }
    }

    @Test
    void rollingSurvivesExtremeByteValues() {
        byte[] d = new byte[400];
        Random rnd = new Random(9);
        for (int i = 0; i < d.length; i++) {
            d[i] = rnd.nextBoolean() ? (byte) 0xFF : 0;
        }
        Rolling r = Rolling.of(d, 0, 100);
        for (int pos = 0; pos + 100 < d.length; pos++) {
            r.roll(d[pos], d[pos + 100]);
            assertEquals(Rolling.weak(d, pos + 1, 100), r.value());
        }
    }

    @Test
    void orderMattersBecauseOfPositionWeights() {
        assertNotEquals(Rolling.weak(new byte[] {1, 2}, 0, 2), Rolling.weak(new byte[] {2, 1}, 0, 2));
    }

    @Test
    void rejectsBadArguments() {
        byte[] d = new byte[4];
        assertThrows(IllegalArgumentException.class, () -> Rolling.weak(d, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Rolling.weak(d, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> Rolling.weak(d, -1, 2));
        assertThrows(NullPointerException.class, () -> Rolling.weak(null, 0, 1));
    }
}
