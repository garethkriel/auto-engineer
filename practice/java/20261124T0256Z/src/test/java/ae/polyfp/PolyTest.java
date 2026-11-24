package ae.polyfp;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PolyTest {

    static Poly random(long p, int maxDeg, Random r) {
        int n = r.nextInt(maxDeg + 1) + 1;
        long[] c = new long[n];
        for (int i = 0; i < n; i++) {
            c[i] = r.nextInt((int) p);
        }
        return Poly.of(p, c);
    }

    @Test
    void normalisesAndReduces() {
        Poly f = Poly.of(7, 8, -1, 14, 0, 7);
        assertEquals(1, f.degree());
        assertEquals(1, f.coeff(0));
        assertEquals(6, f.coeff(1));
        assertEquals(0, f.coeff(5));
        assertEquals(-1, Poly.zero(7).degree());
        assertTrue(Poly.of(7, 7, 14).isZero());
        assertEquals(Poly.of(7, 1, 6), f);
        assertEquals(Poly.of(7, 1, 6).hashCode(), f.hashCode());
        assertNotEquals(Poly.of(5, 1, 1), Poly.of(7, 1, 1));
    }

    @Test
    void rejectsNonPrimeOrHugeModulus() {
        assertThrows(IllegalArgumentException.class, () -> Poly.of(9, 1));
        assertThrows(IllegalArgumentException.class, () -> Poly.of(1, 1));
        assertThrows(IllegalArgumentException.class, () -> Poly.of(4294967311L, 1));
        assertThrows(IllegalArgumentException.class, () -> Poly.of(7, 1).add(Poly.of(5, 1)));
    }

    @Test
    void literalArithmeticMod5() {
        Poly a = Poly.of(5, 1, 2, 3); // 3x^2+2x+1
        Poly b = Poly.of(5, 4, 4);    // 4x+4
        assertEquals(Poly.of(5, 0, 1, 3), a.add(b));
        assertEquals(Poly.of(5, 2, 3, 3), a.sub(b));
        // (3x^2+2x+1)(4x+4) = 12x^3 + 20x^2 + 12x + 4 ≡ 2x^3 + 0x^2 + 2x + 4
        assertEquals(Poly.of(5, 4, 2, 0, 2), a.mul(b));
        assertEquals(Poly.zero(5), a.mul(0));
        assertEquals(Poly.of(5, 4, 3, 2), a.neg());
        assertEquals(Poly.of(5, 2, 1), a.derivative()); // 6x+2 = x+2
    }

    @Test
    void derivativeDropsMultiplesOfP() {
        // d/dx (x^5 + 3x^3 + x) mod 5 = 5x^4 + 9x^2 + 1 = 4x^2 + 1
        assertEquals(Poly.of(5, 1, 0, 4), Poly.of(5, 0, 1, 0, 3, 0, 1).derivative());
        assertEquals(Poly.zero(5), Poly.of(5, 3).derivative());
    }

    @Test
    void evalUsesHorner() {
        Poly f = Poly.of(7, 1, 2, 3); // 3x^2+2x+1
        assertEquals(1, f.eval(0));
        assertEquals(6, f.eval(1));
        assertEquals(17 % 7, f.eval(2));
        assertEquals(f.eval(3), f.eval(10));
        assertEquals(f.eval(6), f.eval(-1));
        assertEquals(0, Poly.zero(7).eval(3));
    }

    @Test
    void divModIdentityOnRandomInputs() {
        Random r = new Random(11);
        long[] primes = {2, 3, 5, 7, 13, 101, 2147483647L};
        for (int t = 0; t < 600; t++) {
            long p = primes[r.nextInt(primes.length)];
            Poly a = random(p, 9, r);
            Poly b = random(p, 5, r);
            if (b.isZero()) {
                continue;
            }
            Poly[] qr = a.divMod(b);
            assertEquals(a, qr[0].mul(b).add(qr[1]), "a=" + a + " b=" + b);
            assertTrue(qr[1].degree() < b.degree(), "remainder degree");
            assertEquals(qr[1], a.mod(b));
        }
    }

    @Test
    void divModLiteralAndZeroDivisor() {
        // x^3 + 1 = (x + 1)(x^2 - x + 1) over GF(7)
        Poly[] qr = Poly.of(7, 1, 0, 0, 1).divMod(Poly.of(7, 1, 1));
        assertEquals(Poly.of(7, 1, 6, 1), qr[0]);
        assertTrue(qr[1].isZero());
        assertThrows(IllegalArgumentException.class, () -> Poly.one(7).divMod(Poly.zero(7)));
    }

    @Test
    void gcdIsMonicAndDivisibleByBoth() {
        long p = 7;
        Poly g = Poly.of(p, 3, 1, 1);          // x^2+x+3
        Poly a = g.mul(Poly.of(p, 2, 1)).mul(5);
        Poly b = g.mul(Poly.of(p, 1, 0, 1));
        Poly d = Poly.gcd(a, b);
        assertEquals(g, d);
        assertEquals(1, d.lead());
        assertEquals(Poly.one(p), Poly.gcd(Poly.of(p, 1, 1), Poly.of(p, 2, 1)));
        assertEquals(Poly.of(p, 1, 1), Poly.gcd(Poly.zero(p), Poly.of(p, 3, 3)));
        assertEquals(Poly.zero(p), Poly.gcd(Poly.zero(p), Poly.zero(p)));
    }

    @Test
    void gcdRandomizedDividesBoth() {
        Random r = new Random(5);
        for (int t = 0; t < 300; t++) {
            long p = new long[] {3, 5, 11}[r.nextInt(3)];
            Poly c = random(p, 3, r);
            Poly a = c.mul(random(p, 4, r));
            Poly b = c.mul(random(p, 4, r));
            Poly g = Poly.gcd(a, b);
            if (!a.isZero()) {
                assertTrue(a.mod(g).isZero());
            }
            if (!b.isZero()) {
                assertTrue(b.mod(g).isZero());
            }
            if (!c.isZero()) {
                assertTrue(g.mod(c.monic()).isZero(), "common factor must divide gcd");
            }
        }
    }

    @Test
    void powModMatchesRepeatedMultiplication() {
        long p = 5;
        Poly m = Poly.of(p, 2, 3, 0, 1); // x^3+3x+2
        Poly base = Poly.of(p, 1, 1);
        Poly acc = Poly.one(p);
        for (int e = 0; e < 40; e++) {
            assertEquals(acc, base.powMod(BigInteger.valueOf(e), m), "e=" + e);
            acc = acc.mul(base).mod(m);
        }
        assertEquals(Poly.one(p), base.powMod(BigInteger.ZERO, m));
        assertThrows(IllegalArgumentException.class, () -> base.powMod(BigInteger.valueOf(-1), m));
    }

    @Test
    void fermatForPolynomialsFrobenius() {
        // (a+b)^p = a^p + b^p in characteristic p
        Random r = new Random(3);
        for (long p : new long[] {3, 5, 7}) {
            for (int t = 0; t < 30; t++) {
                Poly a = random(p, 4, r);
                Poly b = random(p, 4, r);
                BigInteger e = BigInteger.valueOf(p);
                Poly m = Poly.of(p, 0).add(Poly.monomial(p, 1, 40));
                assertEquals(a.add(b).powMod(e, m), a.powMod(e, m).add(b.powMod(e, m)));
            }
        }
    }

    @Test
    void interpolateRoundTripAndLiteral() {
        // through (1,2),(2,3),(3,6) mod 7: 
        Poly f = Poly.interpolate(7, new long[] {1, 2, 3}, new long[] {2, 3, 6});
        assertEquals(2, f.eval(1));
        assertEquals(3, f.eval(2));
        assertEquals(6, f.eval(3));
        assertTrue(f.degree() <= 2);
        Random r = new Random(8);
        for (int t = 0; t < 100; t++) {
            long p = 101;
            Poly g = random(p, 6, r);
            int n = g.degree() + 1;
            long[] xs = new long[n];
            long[] ys = new long[n];
            for (int i = 0; i < n; i++) {
                xs[i] = i * 3 + 1;
                ys[i] = g.eval(xs[i]);
            }
            assertEquals(g, Poly.interpolate(p, xs, ys));
        }
        assertEquals(Poly.zero(7), Poly.interpolate(7, new long[0], new long[0]));
        assertThrows(IllegalArgumentException.class,
                () -> Poly.interpolate(7, new long[] {1, 8}, new long[] {1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> Poly.interpolate(7, new long[] {1, 2}, new long[] {1}));
    }

    static boolean bruteIrreducible(Poly f) {
        int n = f.degree();
        if (n < 1) {
            return false;
        }
        long p = f.modulus();
        for (int d = 1; d <= n / 2; d++) {
            long[] c = new long[d + 1];
            c[d] = 1;
            long total = 1;
            for (int i = 0; i < d; i++) {
                total *= p;
            }
            for (long code = 0; code < total; code++) {
                long v = code;
                for (int i = 0; i < d; i++) {
                    c[i] = v % p;
                    v /= p;
                }
                if (f.mod(Poly.of(p, c)).isZero()) {
                    return false;
                }
            }
        }
        return true;
    }

    static List<Poly> allMonic(long p, int deg) {
        List<Poly> out = new ArrayList<>();
        long total = 1;
        for (int i = 0; i < deg; i++) {
            total *= p;
        }
        for (long code = 0; code < total; code++) {
            long[] c = new long[deg + 1];
            c[deg] = 1;
            long v = code;
            for (int i = 0; i < deg; i++) {
                c[i] = v % p;
                v /= p;
            }
            out.add(Poly.of(p, c));
        }
        return out;
    }

    @Test
    void irreducibleCountsMatchPublishedFormula() {
        assertEquals(3, countIrreducible(3, 2));
        assertEquals(8, countIrreducible(3, 3));
        assertEquals(18, countIrreducible(3, 4));
        assertEquals(48, countIrreducible(3, 5));
        assertEquals(10, countIrreducible(5, 2));
        assertEquals(40, countIrreducible(5, 3));
        assertEquals(1, countIrreducible(2, 2));   // x^2+x+1
        assertEquals(2, countIrreducible(2, 3));
        assertEquals(3, countIrreducible(2, 4));
        assertEquals(9, countIrreducible(2, 6)); // (64 - 8 - 4 + 2) / 6
    }

    static int countIrreducible(long p, int deg) {
        int n = 0;
        for (Poly f : allMonic(p, deg)) {
            if (f.isIrreducible()) {
                n++;
            }
        }
        return n;
    }

    @Test
    void irreducibilityAgreesWithBruteForce() {
        for (long p : new long[] {2, 3, 5}) {
            for (int deg = 1; deg <= 6; deg++) {
                if (Math.pow(p, deg) > 20000) {
                    continue;
                }
                for (Poly f : allMonic(p, deg)) {
                    assertEquals(bruteIrreducible(f), f.isIrreducible(), f.toString());
                    assertEquals(bruteIrreducible(f), f.mul(3 % p == 0 ? 1 : 3).isIrreducible(),
                            "scaling must not matter: " + f);
                }
            }
        }
        assertFalse(Poly.zero(5).isIrreducible());
        assertFalse(Poly.of(5, 3).isIrreducible());
        assertTrue(Poly.of(5, 3, 2).isIrreducible());
    }

    @Test
    void largeIrreducibleAndReducible() {
        // x^2 + 1 over p = 1000003 (p ≡ 3 mod 4): irreducible; over 1000033 (≡ 1 mod 4): reducible.
        assertTrue(Poly.of(1000003, 1, 0, 1).isIrreducible());
        assertFalse(Poly.of(1000033, 1, 0, 1).isIrreducible());
    }
}
