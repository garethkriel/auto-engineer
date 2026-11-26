package ae.linalg;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MatrixTest {
    static Matrix m(long[]... rows) {
        return Matrix.of(rows);
    }

    @Test
    void productLiteral() {
        Matrix a = m(new long[] {1, 2, 3}, new long[] {4, 5, 6});
        Matrix b = m(new long[] {7, 8}, new long[] {9, 10}, new long[] {11, 12});
        assertEquals(m(new long[] {58, 64}, new long[] {139, 154}), a.times(b));
        assertEquals(2, a.times(b).rows());
        assertEquals(2, a.times(b).cols());
    }

    @Test
    void transposeSwapsDimensionsAndEntries() {
        Matrix a = m(new long[] {1, 2, 3}, new long[] {4, 5, 6});
        Matrix t = a.transpose();
        assertEquals(3, t.rows());
        assertEquals(2, t.cols());
        assertEquals(Rational.of(6), t.get(2, 1));
        assertEquals(a, t.transpose());
    }

    @Test
    void fibonacciPower() {
        Matrix f = m(new long[] {1, 1}, new long[] {1, 0});
        // F(10)=55, F(11)=89, F(9)=34
        assertEquals(m(new long[] {89, 55}, new long[] {55, 34}), f.pow(10));
        assertEquals(Matrix.identity(2), f.pow(0));
        assertEquals(f, f.pow(1));
    }

    @Test
    void sumDifferenceScaleTrace() {
        Matrix a = m(new long[] {1, 2}, new long[] {3, 4});
        assertEquals(m(new long[] {2, 4}, new long[] {6, 8}), a.plus(a));
        assertEquals(Matrix.zero(2, 2), a.minus(a));
        assertEquals(Rational.of(5), a.trace());
        assertEquals(Rational.of(1, 2), a.times(Rational.of(1, 2)).get(0, 0));
        assertEquals(Rational.of(2), a.times(Rational.of(1, 2)).get(1, 1));
    }

    @Test
    void applyMultipliesColumnVector() {
        Matrix a = m(new long[] {1, 2}, new long[] {3, 4});
        Rational[] y = a.apply(new Rational[] {Rational.of(5), Rational.of(-1)});
        assertArrayEquals(new Rational[] {Rational.of(3), Rational.of(11)}, y);
    }

    @Test
    void rejectsBadShapes() {
        assertThrows(IllegalArgumentException.class, () -> m(new long[] {1, 2}, new long[] {3}));
        assertThrows(IllegalArgumentException.class, () -> Matrix.of(new long[0][0]));
        assertThrows(IllegalArgumentException.class, () -> Matrix.zero(0, 3));
        Matrix a = Matrix.zero(2, 3);
        assertThrows(IllegalArgumentException.class, () -> a.times(a));
        assertThrows(IllegalArgumentException.class, () -> a.plus(Matrix.zero(3, 2)));
        assertThrows(IllegalArgumentException.class, a::trace);
        assertThrows(IllegalArgumentException.class, () -> a.pow(2));
        assertThrows(IllegalArgumentException.class, () -> Matrix.identity(2).pow(-1));
        assertThrows(IllegalArgumentException.class, () -> a.apply(new Rational[] {Rational.ONE}));
        assertThrows(IndexOutOfBoundsException.class, () -> a.get(2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> a.get(0, -1));
    }

    @Test
    void isImmutable() {
        long[][] raw = {{1, 2}, {3, 4}};
        Matrix a = Matrix.of(raw);
        raw[0][0] = 99;
        assertEquals(Rational.ONE, a.get(0, 0));
        Rational[] row = a.row(0);
        row[0] = Rational.of(77);
        assertEquals(Rational.ONE, a.get(0, 0));
        Rational[] col = a.col(1);
        col[1] = Rational.of(77);
        assertEquals(Rational.of(4), a.get(1, 1));
    }

    @Test
    void toStringIsLineBased() {
        Matrix half = m(new long[] {1, -2}, new long[] {3, 4}).times(Rational.of(1, 2));
        assertEquals("1/2 -1\n3/2 2", half.toString());
    }

    @Test
    void raggedInEitherDirectionAndAliasingOfRationalInput() {
        // second row longer than the first, and (control) shorter
        assertThrows(IllegalArgumentException.class, () -> m(new long[] {1}, new long[] {2, 3}));
        assertThrows(IllegalArgumentException.class, () -> m(new long[] {1, 2}, new long[] {3}));
        Rational[][] raw = {{Rational.ONE, Rational.of(2)}, {Rational.of(3), Rational.of(4)}};
        Matrix a = Matrix.ofRational(raw);
        raw[0][0] = Rational.of(99);
        raw[1] = new Rational[] {Rational.ZERO, Rational.ZERO};
        assertEquals(Rational.ONE, a.get(0, 0));
        assertEquals(Rational.of(4), a.get(1, 1));
        assertThrows(IllegalArgumentException.class, () -> Matrix.ofRational(new Rational[][] {{Rational.ONE, null}}));
    }
}
