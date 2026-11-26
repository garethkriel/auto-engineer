package ae.linalg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GramSchmidtTest {
    static Rational[] v(long... xs) {
        Rational[] r = new Rational[xs.length];
        for (int i = 0; i < xs.length; i++) {
            r[i] = Rational.of(xs[i]);
        }
        return r;
    }

    @Test
    void literalBasis() {
        List<Rational[]> out = GramSchmidt.orthogonalize(List.of(v(1, 1, 0), v(1, 0, 1), v(0, 1, 1)));
        assertEquals(3, out.size());
        assertArrayEquals(v(1, 1, 0), out.get(0));
        // (1,0,1) - (1/2)(1,1,0) = (1/2, -1/2, 1)
        assertArrayEquals(new Rational[] {Rational.of(1, 2), Rational.of(-1, 2), Rational.ONE}, out.get(1));
        // (0,1,1) - (1/2)(1,1,0) - ((1/2)/(3/2))(1/2,-1/2,1) = (-2/3, 2/3, 2/3)
        assertArrayEquals(new Rational[] {Rational.of(-2, 3), Rational.of(2, 3), Rational.of(2, 3)}, out.get(2));
    }

    @Test
    void dependentVectorsAreDropped() {
        List<Rational[]> out = GramSchmidt.orthogonalize(List.of(v(1, 2), v(2, 4), v(0, 1), v(5, 5)));
        assertEquals(2, out.size());
        assertEquals(Rational.ZERO, GramSchmidt.dot(out.get(0), out.get(1)));
    }

    @Test
    void zeroVectorsDroppedAndEmptyInput() {
        assertTrue(GramSchmidt.orthogonalize(List.of(v(0, 0), v(0, 0))).isEmpty());
        assertTrue(GramSchmidt.orthogonalize(List.of()).isEmpty());
    }

    @Test
    void projectionLiteral() {
        List<Rational[]> basis = GramSchmidt.orthogonalize(List.of(v(1, 0, 0), v(0, 1, 0)));
        assertArrayEquals(v(3, 4, 0), GramSchmidt.project(v(3, 4, 5), basis));
        // onto the line (1,1): (2,0) -> (1,1)
        assertArrayEquals(v(1, 1), GramSchmidt.project(v(2, 0), java.util.Collections.singletonList(v(1, 1))));
        assertArrayEquals(v(0, 0), GramSchmidt.project(v(2, 0), List.of()));
    }

    @Test
    void mismatchedLengthsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GramSchmidt.orthogonalize(List.of(v(1, 2), v(1, 2, 3))));
        assertThrows(IllegalArgumentException.class, () -> GramSchmidt.dot(v(1), v(1, 2)));
    }
}
