package ae.linalg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CharPolyTest {
    static Rational[] c(long... xs) {
        Rational[] r = new Rational[xs.length];
        for (int i = 0; i < xs.length; i++) {
            r[i] = Rational.of(xs[i]);
        }
        return r;
    }

    @Test
    void twoByTwoLiteral() {
        // det(xI - A) = x^2 - tr x + det = x^2 - 5x - 2 for [[1,2],[3,4]]
        assertArrayEquals(c(-2, -5, 1), CharPoly.coefficients(Matrix.of(new long[][] {{1, 2}, {3, 4}})));
    }

    @Test
    void threeByThreeLiteral() {
        // companion-like: [[0,1,0],[0,0,1],[6,-11,6]] has char poly x^3 - 6x^2 + 11x - 6 = (x-1)(x-2)(x-3)
        Matrix a = Matrix.of(new long[][] {{0, 1, 0}, {0, 0, 1}, {6, -11, 6}});
        assertArrayEquals(c(-6, 11, -6, 1), CharPoly.coefficients(a));
    }

    @Test
    void oneByOneAndIdentity() {
        assertArrayEquals(c(-7, 1), CharPoly.coefficients(Matrix.of(new long[][] {{7}})));
        // (x-1)^3 = x^3 - 3x^2 + 3x - 1
        assertArrayEquals(c(-1, 3, -3, 1), CharPoly.coefficients(Matrix.identity(3)));
    }

    @Test
    void rationalEigenvaluesWithMultiplicity() {
        Matrix a = Matrix.of(new long[][] {{0, 1, 0}, {0, 0, 1}, {6, -11, 6}});
        assertEquals(Map.of(Rational.of(1), 1, Rational.of(2), 1, Rational.of(3), 1), CharPoly.rationalEigenvalues(a));
        assertEquals(Map.of(Rational.ONE, 3), CharPoly.rationalEigenvalues(Matrix.identity(3)));
        // upper triangular with diagonal 2, 2, -1/2 : entries scaled so denominators appear
        Matrix t = Matrix.of(new long[][] {{4, 1, 0}, {0, 4, 3}, {0, 0, -1}}).times(Rational.of(1, 2));
        Map<Rational, Integer> ev = CharPoly.rationalEigenvalues(t);
        assertEquals(Map.of(Rational.of(2), 2, Rational.of(-1, 2), 1), ev);
    }

    @Test
    void irrationalRootsAreNotReported() {
        // x^2 - 5x - 2 has discriminant 33, no rational roots
        assertTrue(CharPoly.rationalEigenvalues(Matrix.of(new long[][] {{1, 2}, {3, 4}})).isEmpty());
        // rotation [[0,-1],[1,0]]: x^2+1, none
        assertTrue(CharPoly.rationalEigenvalues(Matrix.of(new long[][] {{0, -1}, {1, 0}})).isEmpty());
        // x^2 - 2 mixed with a rational root: diag block [[0,2],[1,0]] and 3 -> (x^2-2)(x-3)
        Matrix a = Matrix.of(new long[][] {{0, 2, 0}, {1, 0, 0}, {0, 0, 3}});
        assertEquals(Map.of(Rational.of(3), 1), CharPoly.rationalEigenvalues(a));
    }

    @Test
    void zeroEigenvalueHandledWithoutRationalRootDivisionByZero() {
        Matrix a = Matrix.of(new long[][] {{1, 1}, {1, 1}});   // x^2 - 2x = x(x-2)
        assertEquals(Map.of(Rational.ZERO, 1, Rational.of(2), 1), CharPoly.rationalEigenvalues(a));
        assertEquals(Map.of(Rational.ZERO, 2), CharPoly.rationalEigenvalues(Matrix.zero(2, 2)));
    }

    @Test
    void eigenspaceLiteral() {
        Matrix a = Matrix.of(new long[][] {{2, 1}, {0, 2}});   // defective: one eigenvector for 2
        List<Rational[]> basis = CharPoly.eigenspace(a, Rational.of(2));
        assertEquals(1, basis.size());
        assertArrayEquals(c(1, 0), basis.get(0));
        // diag(2,2) has a 2-dimensional eigenspace: control differing only by the off-diagonal entry
        assertEquals(2, CharPoly.eigenspace(Matrix.of(new long[][] {{2, 0}, {0, 2}}), Rational.of(2)).size());
        // a non-eigenvalue has an empty eigenspace
        assertTrue(CharPoly.eigenspace(a, Rational.of(3)).isEmpty());
    }
}
