package ae.linalg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EliminationTest {
    static Matrix m(long[]... rows) {
        return Matrix.of(rows);
    }

    static Rational[] v(long... xs) {
        Rational[] r = new Rational[xs.length];
        for (int i = 0; i < xs.length; i++) {
            r[i] = Rational.of(xs[i]);
        }
        return r;
    }

    @Test
    void rrefLiteralWithFreeColumn() {
        // columns: 0 pivot, 1 = 2*col0 (free), 2 pivot, 3 free
        Matrix a = m(new long[] {1, 2, 0, 3}, new long[] {2, 4, 1, 7}, new long[] {3, 6, 1, 10});
        Elimination.Rref r = Elimination.rref(a);
        assertEquals(List.of(0, 2), r.pivots());
        // row3 = row1 + row2 -> zero row. After reduction: [1 2 0 3], [0 0 1 1], [0 0 0 0]
        assertEquals(m(new long[] {1, 2, 0, 3}, new long[] {0, 0, 1, 1}, new long[] {0, 0, 0, 0}), r.matrix());
        assertEquals(2, Elimination.rank(a));
    }

    @Test
    void rrefNeedsRowSwapAndScaling() {
        Matrix a = m(new long[] {0, 2, 4}, new long[] {3, 0, 6});
        Elimination.Rref r = Elimination.rref(a);
        assertEquals(m(new long[] {1, 0, 2}, new long[] {0, 1, 2}), r.matrix());
        assertEquals(List.of(0, 1), r.pivots());
    }

    @Test
    void determinantLiterals() {
        assertEquals(Rational.of(-2), Elimination.determinant(m(new long[] {1, 2}, new long[] {3, 4})));
        // swap needed: leading zero. det = -(1*... ) computed by cofactor: 0*(..) - 2*(3*4-... )
        // [[0,2,1],[3,1,0],[1,0,2]] : 0*(2-0) - 2*(6-0) + 1*(0-1) = -12 - 1 = -13
        assertEquals(Rational.of(-13), Elimination.determinant(m(new long[] {0, 2, 1}, new long[] {3, 1, 0}, new long[] {1, 0, 2})));
        assertEquals(Rational.ZERO, Elimination.determinant(m(new long[] {1, 2}, new long[] {2, 4})));
        assertEquals(Rational.of(7), Elimination.determinant(m(new long[] {7})));
        assertEquals(Rational.ONE, Elimination.determinant(Matrix.identity(5)));
    }

    @Test
    void inverseLiteral() {
        Matrix a = m(new long[] {2, 1}, new long[] {5, 3});
        Matrix inv = Elimination.inverse(a);
        assertEquals(m(new long[] {3, -1}, new long[] {-5, 2}), inv);
        Matrix b = m(new long[] {1, 2}, new long[] {3, 4});
        Matrix invB = Elimination.inverse(b);
        assertEquals(Rational.of(-2), invB.get(0, 0));
        assertEquals(Rational.ONE, invB.get(0, 1));
        assertEquals(Rational.of(3, 2), invB.get(1, 0));
        assertEquals(Rational.of(-1, 2), invB.get(1, 1));
    }

    @Test
    void singularAndNonSquareInverseFail() {
        assertThrows(SingularMatrixException.class, () -> Elimination.inverse(m(new long[] {1, 2}, new long[] {2, 4})));
        assertThrows(IllegalArgumentException.class, () -> Elimination.inverse(Matrix.zero(2, 3)));
        assertThrows(IllegalArgumentException.class, () -> Elimination.determinant(Matrix.zero(2, 3)));
    }

    @Test
    void nullspaceLiteral() {
        Matrix a = m(new long[] {1, 2, 0, 3}, new long[] {2, 4, 1, 7}, new long[] {3, 6, 1, 10});
        List<Rational[]> basis = Elimination.nullspace(a);
        assertEquals(2, basis.size());
        // free columns 1 and 3. Rref rows: x0 + 2x1 + 3x3 = 0, x2 + x3 = 0
        assertArrayEquals(v(-2, 1, 0, 0), basis.get(0));
        assertArrayEquals(v(-3, 0, -1, 1), basis.get(1));
    }

    @Test
    void nullspaceOfFullRankIsEmptyAndZeroMatrixIsAll() {
        assertTrue(Elimination.nullspace(Matrix.identity(3)).isEmpty());
        List<Rational[]> all = Elimination.nullspace(Matrix.zero(2, 3));
        assertEquals(3, all.size());
        assertArrayEquals(v(1, 0, 0), all.get(0));
        assertArrayEquals(v(0, 1, 0), all.get(1));
        assertArrayEquals(v(0, 0, 1), all.get(2));
    }

    @Test
    void columnSpaceBasisTakesOriginalColumns() {
        Matrix a = m(new long[] {1, 2, 0, 3}, new long[] {2, 4, 1, 7}, new long[] {3, 6, 1, 10});
        List<Rational[]> cols = Elimination.columnSpaceBasis(a);
        assertEquals(2, cols.size());
        assertArrayEquals(v(1, 2, 3), cols.get(0));
        assertArrayEquals(v(0, 1, 1), cols.get(1));
    }

    @Test
    void solveUnique() {
        Matrix a = m(new long[] {2, 1}, new long[] {5, 3});
        Solution s = Elimination.solve(a, v(4, 11));
        Solution.Unique u = assertInstanceOf(Solution.Unique.class, s);
        // 2x+y=4, 5x+3y=11 -> x=1, y=2
        assertArrayEquals(v(1, 2), u.x());
    }

    @Test
    void solveInfiniteHasParticularWithZeroFreeColumns() {
        // x + 2y + z = 3 ; 2x + 4y + 3z = 8  -> pivots 0,2 ; free y
        Matrix a = m(new long[] {1, 2, 1}, new long[] {2, 4, 3});
        Solution.Infinite inf = assertInstanceOf(Solution.Infinite.class, Elimination.solve(a, v(3, 8)));
        // z = 2, x = 1 - 2y
        assertArrayEquals(v(1, 0, 2), inf.particular());
        assertEquals(1, inf.nullBasis().size());
        assertArrayEquals(v(-2, 1, 0), inf.nullBasis().get(0));
    }

    @Test
    void solveInconsistent() {
        Matrix a = m(new long[] {1, 1}, new long[] {2, 2});
        assertInstanceOf(Solution.Inconsistent.class, Elimination.solve(a, v(1, 3)));
        // control: same matrix, consistent right-hand side
        assertInstanceOf(Solution.Infinite.class, Elimination.solve(a, v(1, 2)));
    }

    @Test
    void solveOverdeterminedConsistent() {
        Matrix a = m(new long[] {1, 0}, new long[] {0, 1}, new long[] {1, 1});
        assertArrayEquals(v(2, 3), assertInstanceOf(Solution.Unique.class, Elimination.solve(a, v(2, 3, 5))).x());
        assertInstanceOf(Solution.Inconsistent.class, Elimination.solve(a, v(2, 3, 6)));
    }

    @Test
    void solveRejectsWrongRhsLength() {
        assertThrows(IllegalArgumentException.class, () -> Elimination.solve(Matrix.identity(2), v(1, 2, 3)));
    }

    @Test
    void bareissLiterals() {
        assertEquals(java.math.BigInteger.valueOf(-13), Bareiss.determinant(new long[][] {{0, 2, 1}, {3, 1, 0}, {1, 0, 2}}));
        assertEquals(java.math.BigInteger.ZERO, Bareiss.determinant(new long[][] {{1, 2}, {2, 4}}));
        // zero pivot in the middle of the elimination: [[1,2,3],[2,4,1],[3,5,7]] -> 1*(28-5) - 2*(14-3) + 3*(10-12) = 23 - 22 - 6 = -5
        assertEquals(java.math.BigInteger.valueOf(-5), Bareiss.determinant(new long[][] {{1, 2, 3}, {2, 4, 1}, {3, 5, 7}}));
        assertEquals(java.math.BigInteger.valueOf(9), Bareiss.determinant(new long[][] {{9}}));
        assertThrows(IllegalArgumentException.class, () -> Bareiss.determinant(new long[][] {{1, 2}}));
    }
}
