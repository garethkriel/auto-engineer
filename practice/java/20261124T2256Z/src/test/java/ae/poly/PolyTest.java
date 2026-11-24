package ae.poly;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PolyTest {
    private static final long P = 998244353L;

    private static Poly rnd(Random r, int terms) {
        long[] c = new long[terms];
        for (int i = 0; i < terms; i++) c[i] = r.nextInt((int) P);
        return Poly.of(c);
    }

    private static Poly naiveMul(Poly a, Poly b) {
        if (a.degree() < 0 || b.degree() < 0) return Poly.ZERO;
        long[] c = new long[a.degree() + b.degree() + 1];
        for (int i = 0; i <= a.degree(); i++)
            for (int j = 0; j <= b.degree(); j++)
                c[i + j] = Mod.add(c[i + j], Mod.mul(a.coeff(i), b.coeff(j)));
        return Poly.of(c);
    }

    @Test
    void normalisesTrailingZerosAndNegatives() {
        Poly p = Poly.of(1, -1, 0, 0);
        assertEquals(1, p.degree());
        assertEquals(P - 1, p.coeff(1));
        assertEquals(0, p.coeff(5));
        assertEquals(-1, Poly.of(0, 0, P).degree());
        assertEquals(Poly.ZERO, Poly.of());
        assertEquals(Poly.of(1, 2), Poly.of(1, 2 + P, 0));
        assertEquals(Poly.of(1, 2).hashCode(), Poly.of(1, 2, 0).hashCode());
    }

    @Test
    void addSubLiterals() {
        Poly a = Poly.of(1, 2, 3);
        Poly b = Poly.of(4, 5);
        assertEquals(Poly.of(5, 7, 3), a.add(b));
        assertEquals(Poly.of(-3, -3, 3), a.sub(b));
        assertEquals(Poly.ZERO, a.sub(a));
    }

    @Test
    void mulLiteral() {
        // (1 + x)(1 - x) = 1 - x^2
        assertEquals(Poly.of(1, 0, -1), Poly.of(1, 1).mul(Poly.of(1, -1)));
        assertEquals(Poly.ZERO, Poly.of(1, 1).mul(Poly.ZERO));
    }

    @Test
    void nttMulMatchesNaiveAcrossThreshold() {
        Random r = new Random(5);
        for (int n : new int[] {1, 2, 17, 31, 32, 33, 64, 100, 300}) {
            for (int m : new int[] {1, 5, 32, 33, 257}) {
                Poly a = rnd(r, n);
                Poly b = rnd(r, m);
                assertEquals(naiveMul(a, b), a.mul(b), n + "x" + m);
            }
        }
    }

    @Test
    void truncateAndDerivative() {
        Poly p = Poly.of(7, 6, 5, 4);
        assertEquals(Poly.of(7, 6), p.truncate(2));
        assertEquals(Poly.ZERO, p.truncate(0));
        assertEquals(p, p.truncate(10));
        assertEquals(Poly.of(6, 10, 12), p.derivative());
        assertEquals(Poly.ZERO, Poly.of(9).derivative());
    }

    @Test
    void evalHorner() {
        // 3 + 2x + x^2 at x = 5 -> 38
        assertEquals(38, Poly.of(3, 2, 1).eval(5));
        assertEquals(P - 1, Poly.of(1, 1).eval(P - 2)); // 1 + (p-2) = p-1
        assertEquals(0, Poly.ZERO.eval(12345));
    }

    @Test
    void inverseOfOneMinusXIsGeometricSeries() {
        Poly g = Poly.of(1, -1).inverse(6);
        assertEquals(Poly.of(1, 1, 1, 1, 1, 1), g);
    }

    @Test
    void inverseGivesFibonacciGeneratingFunction() {
        Poly g = Poly.of(1, -1, -1).inverse(8);
        assertEquals(Poly.of(1, 1, 2, 3, 5, 8, 13, 21), g);
    }

    @Test
    void inverseTimesOriginalIsOneForManyLengths() {
        Random r = new Random(6);
        for (int n : new int[] {1, 2, 3, 4, 5, 16, 17, 100, 129, 1000}) {
            Poly f = rnd(r, 40);
            if (f.coeff(0) == 0) f = f.add(Poly.of(1));
            Poly g = f.inverse(n);
            assertTrue(g.degree() < n, "degree bound n=" + n);
            assertEquals(Poly.of(1), f.mul(g).truncate(n), "n=" + n);
        }
    }

    @Test
    void inverseRejectsZeroConstantTermAndBadLength() {
        assertThrows(ArithmeticException.class, () -> Poly.of(0, 1).inverse(4));
        assertThrows(ArithmeticException.class, () -> Poly.ZERO.inverse(1));
        assertThrows(IllegalArgumentException.class, () -> Poly.of(1).inverse(0));
    }

    @Test
    void divModLiterals() {
        Poly.QR a = Poly.of(-1, 0, 1).divMod(Poly.of(-1, 1)); // (x^2-1)/(x-1)
        assertEquals(Poly.of(1, 1), a.q());
        assertEquals(Poly.ZERO, a.r());
        Poly.QR b = Poly.of(5, 2, 0, 1).divMod(Poly.of(2, 1)); // x^3+2x+5 / (x+2) = x^2-2x+6 rem -7
        assertEquals(Poly.of(6, -2, 1), b.q());
        assertEquals(Poly.of(-7), b.r());
        Poly.QR c = Poly.of(1, 1).divMod(Poly.of(0, 0, 1)); // deg a < deg d
        assertEquals(Poly.ZERO, c.q());
        assertEquals(Poly.of(1, 1), c.r());
    }

    @Test
    void divModInvariantRandom() {
        Random r = new Random(7);
        for (int i = 0; i < 300; i++) {
            Poly a = rnd(r, 1 + r.nextInt(120));
            Poly d = rnd(r, 1 + r.nextInt(60));
            if (d.degree() < 0) continue;
            Poly.QR qr = a.divMod(d);
            assertEquals(a, qr.q().mul(d).add(qr.r()), "a = q*d + r");
            assertTrue(qr.r().degree() < d.degree(), "deg r < deg d");
        }
    }

    @Test
    void divModByZeroThrows() {
        assertThrows(ArithmeticException.class, () -> Poly.of(1).divMod(Poly.ZERO));
    }

    @Test
    void interpolateRoundTrip() {
        Random r = new Random(8);
        for (int n : new int[] {1, 2, 3, 10, 40}) {
            Poly f = rnd(r, n);
            long[] xs = new long[n];
            long[] ys = new long[n];
            for (int i = 0; i < n; i++) {
                xs[i] = 10 + 3L * i; // distinct
                ys[i] = f.eval(xs[i]);
            }
            assertEquals(f, Poly.interpolate(xs, ys), "n=" + n);
        }
    }

    @Test
    void interpolateLiteralAndErrors() {
        // points (0,1),(1,3),(2,7): 1 + x + x^2
        assertEquals(Poly.of(1, 1, 1), Poly.interpolate(new long[] {0, 1, 2}, new long[] {1, 3, 7}));
        assertEquals(Poly.ZERO, Poly.interpolate(new long[] {4}, new long[] {0}));
        assertThrows(IllegalArgumentException.class, () -> Poly.interpolate(new long[] {1, 1}, new long[] {2, 3}));
        assertThrows(IllegalArgumentException.class, () -> Poly.interpolate(new long[] {1, 2}, new long[] {2}));
        assertThrows(IllegalArgumentException.class, () -> Poly.interpolate(new long[] {}, new long[] {}));
    }

    @Test
    void largeProductAndDivisionAreFastAndCorrect() {
        Random r = new Random(9);
        long t0 = System.nanoTime();
        Poly a = rnd(r, 1 << 16);
        Poly b = rnd(r, 1 << 16);
        Poly prod = a.mul(b);
        assertEquals((1 << 17) - 2, prod.degree());
        for (int i = 0; i < 5; i++) {
            long x = 1 + r.nextInt(1000000);
            assertEquals(Mod.mul(a.eval(x), b.eval(x)), prod.eval(x));
        }
        Poly n = rnd(r, 50000);
        Poly d = rnd(r, 20000);
        Poly.QR qr = n.divMod(d);
        assertTrue(qr.r().degree() < d.degree());
        for (int i = 0; i < 5; i++) {
            long x = 1 + r.nextInt(1000000);
            assertEquals(n.eval(x), Mod.add(Mod.mul(qr.q().eval(x), d.eval(x)), qr.r().eval(x)));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 20000, "took " + ms + " ms");
    }
}
