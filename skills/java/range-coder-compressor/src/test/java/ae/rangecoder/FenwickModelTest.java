package ae.rangecoder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class FenwickModelTest {

    /** Naive oracle: plain frequency array with the same update and rescale rules. */
    private static final class Naive {
        final int[] f;
        Naive(int n) { f = new int[n]; java.util.Arrays.fill(f, 1); }
        int total() { int t = 0; for (int x : f) t += x; return t; }
        int cum(int s) { int t = 0; for (int i = 0; i < s; i++) t += f[i]; return t; }
        void update(int s) {
            if (total() + FenwickModel.INCREMENT > FenwickModel.LIMIT) {
                for (int i = 0; i < f.length; i++) f[i] = (f[i] + 1) / 2;
            }
            f[s] += FenwickModel.INCREMENT;
        }
        int find(int target) {
            int t = 0;
            for (int i = 0; i < f.length; i++) { t += f[i]; if (target < t) return i; }
            throw new AssertionError("target " + target);
        }
    }

    @Test
    void freshModelIsUniform() {
        FenwickModel m = new FenwickModel(5);
        assertEquals(5, m.total());
        for (int s = 0; s < 5; s++) {
            assertEquals(s, m.cumFreq(s));
            assertEquals(1, m.freq(s));
            assertEquals(s, m.findSymbol(s));
        }
    }

    @Test
    void updateAddsIncrementAndShiftsLaterCumulatives() {
        FenwickModel m = new FenwickModel(4);
        m.update(1);
        assertEquals(4 + FenwickModel.INCREMENT, m.total());
        assertEquals(1, m.freq(0));
        assertEquals(1 + FenwickModel.INCREMENT, m.freq(1));
        assertEquals(1, m.cumFreq(1));
        assertEquals(2 + FenwickModel.INCREMENT, m.cumFreq(2));
        assertEquals(1, m.findSymbol(1));
        // symbol 1 owns [1, 1 + (1 + INCREMENT)) = [1, INCREMENT + 2)
        assertEquals(1, m.findSymbol(FenwickModel.INCREMENT + 1));  // last unit of symbol 1
        assertEquals(2, m.findSymbol(FenwickModel.INCREMENT + 2));  // first unit of symbol 2
    }

    @Test
    void randomDifferentialAgainstNaiveIncludingRescales() {
        for (int n : new int[] {1, 2, 3, 7, 16, 100, 256}) {
            Random r = new Random(n * 7919L);
            FenwickModel m = new FenwickModel(n);
            Naive o = new Naive(n);
            int rescales = 0;
            for (int step = 0; step < 6000; step++) {
                // skewed choice so one symbol grows fast and forces rescales
                int s = r.nextInt(4) == 0 ? r.nextInt(n) : Math.min(n - 1, r.nextInt(3));
                int before = m.total();
                m.update(s);
                o.update(s);
                if (m.total() < before) rescales++;
                assertEquals(o.total(), m.total(), "total n=" + n + " step " + step);
                assertTrue(m.total() <= FenwickModel.LIMIT);
                if (step % 97 == 0 || n <= 7) {
                    for (int i = 0; i < n; i++) {
                        assertEquals(o.cum(i), m.cumFreq(i), "cum " + i);
                        assertEquals(o.f[i], m.freq(i), "freq " + i);
                        assertTrue(m.freq(i) >= 1);
                    }
                    int t = o.total();
                    for (int target : new int[] {0, t - 1, t / 2, r.nextInt(t), r.nextInt(t)}) {
                        assertEquals(o.find(target), m.findSymbol(target), "find " + target);
                    }
                }
            }
            if (n >= 2) assertTrue(rescales > 0, "no rescale happened for n=" + n);
        }
    }

    @Test
    void findSymbolIsExhaustivelyConsistentWithCumFreq() {
        FenwickModel m = new FenwickModel(9);
        Random r = new Random(5);
        for (int i = 0; i < 300; i++) m.update(r.nextInt(9));
        for (int target = 0; target < m.total(); target++) {
            int s = m.findSymbol(target);
            assertTrue(m.cumFreq(s) <= target && target < m.cumFreq(s) + m.freq(s), "target " + target);
        }
    }

    @Test
    void rescaleKeepsEveryFrequencyAtLeastOneAndOrder() {
        FenwickModel m = new FenwickModel(3);
        int before;
        do {
            before = m.total();
            m.update(0);
        } while (m.total() >= before);
        assertTrue(m.freq(1) >= 1 && m.freq(2) >= 1);
        assertTrue(m.freq(0) > m.freq(1));
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new FenwickModel(0));
        assertThrows(IllegalArgumentException.class, () -> new FenwickModel(FenwickModel.LIMIT));
        FenwickModel m = new FenwickModel(3);
        assertThrows(IllegalArgumentException.class, () -> m.update(3));
        assertThrows(IllegalArgumentException.class, () -> m.update(-1));
        assertThrows(IllegalArgumentException.class, () -> m.findSymbol(3));
        assertThrows(IllegalArgumentException.class, () -> m.findSymbol(-1));
        assertThrows(IllegalArgumentException.class, () -> m.freq(3));
    }
}
