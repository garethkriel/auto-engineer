package ae.lp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LinearSolveTest {
    @Test
    void solvesRationalSystem() {
        Rational[][] a = {{Rational.of(2), Rational.of(1)}, {Rational.of(1), Rational.of(3)}};
        Rational[] x = LinearSolve.solveSquare(a, new Rational[] {Rational.of(3), Rational.of(5)});
        assertArrayEquals(new Rational[] {Rational.of(4, 5), Rational.of(7, 5)}, x);
    }

    @Test
    void singularSystemReturnsNull() {
        Rational[][] a = {{Rational.of(1), Rational.of(2)}, {Rational.of(2), Rational.of(4)}};
        assertNull(LinearSolve.solveSquare(a, new Rational[] {Rational.of(1), Rational.of(2)}));
    }

    @Test
    void needsRowSwapWhenLeadingZero() {
        Rational[][] a = {{Rational.ZERO, Rational.ONE}, {Rational.ONE, Rational.ZERO}};
        Rational[] x = LinearSolve.solveSquare(a, new Rational[] {Rational.of(7), Rational.of(9)});
        assertArrayEquals(new Rational[] {Rational.of(9), Rational.of(7)}, x);
    }

    @Test
    void inputsAreNotMutated() {
        Rational[][] a = {{Rational.of(2), Rational.of(1)}, {Rational.of(1), Rational.of(3)}};
        Rational[] b = {Rational.of(3), Rational.of(5)};
        LinearSolve.solveSquare(a, b);
        assertEquals(Rational.of(2), a[0][0]);
        assertEquals(Rational.of(3), b[0]);
    }
}
