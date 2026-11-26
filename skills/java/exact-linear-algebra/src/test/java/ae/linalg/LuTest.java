package ae.linalg;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LuTest {
    @Test
    void literalDecompositionNoSwap() {
        Matrix a = Matrix.of(new long[][] {{2, 1}, {4, 5}});
        Lu lu = Lu.decompose(a);
        assertEquals(Matrix.of(new long[][] {{1, 0}, {2, 1}}), lu.l());
        assertEquals(Matrix.of(new long[][] {{2, 1}, {0, 3}}), lu.u());
        assertEquals(Matrix.identity(2), lu.p());
        assertEquals(1, lu.sign());
    }

    @Test
    void swapRecordedInPermutationAndSign() {
        Matrix a = Matrix.of(new long[][] {{0, 1}, {2, 3}});
        Lu lu = Lu.decompose(a);
        assertEquals(Matrix.of(new long[][] {{0, 1}, {1, 0}}), lu.p());
        assertEquals(-1, lu.sign());
        assertEquals(lu.p().times(a), lu.l().times(lu.u()));
        assertEquals(Matrix.of(new long[][] {{2, 3}, {0, 1}}), lu.u());
    }

    @Test
    void singularMatrixStillDecomposes() {
        Matrix a = Matrix.of(new long[][] {{1, 2, 3}, {2, 4, 6}, {1, 0, 1}});
        Lu lu = Lu.decompose(a);
        assertEquals(lu.p().times(a), lu.l().times(lu.u()));
        assertThrows(SingularMatrixException.class, () -> lu.solve(new Rational[] {Rational.ONE, Rational.ONE, Rational.ONE}));
    }

    @Test
    void solveMatchesLiteral() {
        Matrix a = Matrix.of(new long[][] {{0, 2, 1}, {3, 1, 0}, {1, 0, 2}});
        Rational[] b = {Rational.of(5), Rational.of(5), Rational.of(5)};
        Rational[] x = Lu.decompose(a).solve(b);
        for (Rational xi : x) {
            assertNotNull(xi);
        }
        assertArrayEquals(b, a.apply(x));
        // det = -13 and the first unknown from Cramer: x0 = det[[5,2,1],[5,1,0],[5,0,2]]/-13 = 5*(2)-2*(10)+1*(-5) = -15 / -13
        assertEquals(Rational.of(15, 13), x[0]);
    }

    @Test
    void determinantFromLuAgreesWithElimination() {
        Matrix a = Matrix.of(new long[][] {{0, 2, 1}, {3, 1, 0}, {1, 0, 2}});
        assertEquals(Elimination.determinant(a), Lu.decompose(a).determinant());
        assertEquals(Rational.of(-13), Lu.decompose(a).determinant());
    }

    @Test
    void nonSquareRejected() {
        assertThrows(IllegalArgumentException.class, () -> Lu.decompose(Matrix.zero(2, 3)));
    }
}
