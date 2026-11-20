package ae.erasure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class GfMatrixTest {
    private static GfMatrix random(Random rnd, int n) {
        byte[][] a = new byte[n][n];
        for (byte[] row : a) {
            rnd.nextBytes(row);
        }
        return new GfMatrix(a);
    }

    @Test
    void identityIsNeutral() {
        GfMatrix m = random(new Random(1), 5);
        assertEquals(m, m.multiply(GfMatrix.identity(5)));
        assertEquals(m, GfMatrix.identity(5).multiply(m));
    }

    @Test
    void invertTimesOriginalIsIdentityForInvertibleRandomMatrices() {
        Random rnd = new Random(42);
        int inverted = 0;
        for (int i = 0; i < 200; i++) {
            GfMatrix m = random(rnd, 1 + rnd.nextInt(8));
            try {
                GfMatrix inv = m.invert();
                inverted++;
                assertEquals(GfMatrix.identity(m.rows()), m.multiply(inv));
                assertEquals(GfMatrix.identity(m.rows()), inv.multiply(m));
            } catch (ArithmeticException singular) {
                // acceptable: random matrices are sometimes singular
            }
        }
        assertTrue(inverted > 100, "most random matrices are invertible, got " + inverted);
    }

    @Test
    void singularMatrixThrows() {
        GfMatrix zeroRow = new GfMatrix(new byte[][] {{1, 2}, {0, 0}});
        assertThrows(ArithmeticException.class, zeroRow::invert);
        GfMatrix dup = new GfMatrix(new byte[][] {{1, 2}, {1, 2}});
        assertThrows(ArithmeticException.class, dup::invert);
    }

    @Test
    void invertNeedsRowSwap() {
        GfMatrix m = new GfMatrix(new byte[][] {{0, 1}, {1, 0}});
        assertEquals(m, m.invert());
    }

    @Test
    void nonSquareInvertRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GfMatrix(new byte[][] {{1, 2, 3}}).invert());
    }

    @Test
    void multiplyDimensionMismatchRejected() {
        GfMatrix a = new GfMatrix(new byte[][] {{1, 2}});
        assertThrows(IllegalArgumentException.class, () -> a.multiply(a));
    }

    @Test
    void subMatrixPicksRowsInOrderAndCopies() {
        GfMatrix m = new GfMatrix(new byte[][] {{1, 2}, {3, 4}, {5, 6}});
        GfMatrix s = m.subMatrix(new int[] {2, 0});
        assertEquals(new GfMatrix(new byte[][] {{5, 6}, {1, 2}}), s);
        byte[] r = m.row(0);
        r[0] = 99;
        assertEquals(1, m.get(0, 0));
    }

    @Test
    void constructorCopiesInput() {
        byte[][] raw = {{1, 2}, {3, 4}};
        GfMatrix m = new GfMatrix(raw);
        raw[0][0] = 9;
        assertEquals(1, m.get(0, 0));
    }

    @Test
    void ragged_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new GfMatrix(new byte[][] {{1, 2}, {3}}));
    }
}
