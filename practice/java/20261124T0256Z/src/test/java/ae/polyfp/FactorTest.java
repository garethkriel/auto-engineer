package ae.polyfp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FactorTest {

    static Poly product(Factorization fz, long p) {
        Poly acc = Poly.of(p, fz.unit());
        for (Factor f : fz.factors()) {
            for (int i = 0; i < f.multiplicity(); i++) {
                acc = acc.mul(f.poly());
            }
        }
        return acc;
    }

    static void checkStructure(Poly f, Factorization fz) {
        long p = f.modulus();
        assertEquals(f.lead(), fz.unit());
        assertEquals(f, product(fz, p), "product of factors must be the input");
        Factor prev = null;
        for (Factor x : fz.factors()) {
            assertEquals(1, x.poly().lead(), "monic");
            assertTrue(x.multiplicity() >= 1);
            assertTrue(PolyTest.bruteIrreducible(x.poly()), "irreducible: " + x.poly());
            if (prev != null) {
                assertTrue(prev.poly().degree() <= x.poly().degree(), "sorted by degree");
                assertNotEquals(prev.poly(), x.poly(), "distinct");
            }
            prev = x;
        }
    }

    @Test
    void literalFactorisationMod7() {
        // (x+1)^2 (x^2+1) (x+3), x^2+1 is irreducible mod 7 (7 ≡ 3 mod 4)
        long p = 7;
        Poly f = Poly.of(p, 1, 1).mul(Poly.of(p, 1, 1)).mul(Poly.of(p, 1, 0, 1)).mul(Poly.of(p, 3, 1)).mul(4);
        Factorization fz = f.factor();
        assertEquals(4, fz.unit());
        assertEquals(List.of(
                new Factor(Poly.of(p, 1, 1), 2),
                new Factor(Poly.of(p, 3, 1), 1),
                new Factor(Poly.of(p, 1, 0, 1), 1)), fz.factors());
    }

    @Test
    void constantsAndErrors() {
        Factorization c = Poly.of(5, 3).factor();
        assertEquals(3, c.unit());
        assertTrue(c.factors().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Poly.zero(5).factor());
        assertThrows(IllegalArgumentException.class, () -> Poly.of(2, 1, 1).factor());
    }

    @Test
    void xToThePMinusXSplitsIntoAllLinearFactors() {
        for (long p : new long[] {3, 5, 7, 11, 13}) {
            Poly f = Poly.monomial(p, 1, (int) p).sub(Poly.x(p));
            Factorization fz = f.factor();
            assertEquals((int) p, fz.factors().size(), "p=" + p);
            for (Factor x : fz.factors()) {
                assertEquals(1, x.poly().degree());
                assertEquals(1, x.multiplicity());
            }
            checkStructure(f, fz);
        }
    }

    @Test
    void frobeniusPowerCase() {
        // x^p - 1 = (x - 1)^p over GF(p): derivative is zero, exercises the p-th root branch
        for (long p : new long[] {3, 5, 7}) {
            Poly f = Poly.monomial(p, 1, (int) p).sub(Poly.one(p));
            Factorization fz = f.factor();
            assertEquals(List.of(new Factor(Poly.of(p, p - 1, 1), (int) p)), fz.factors());
        }
        // (x^2+1)^3 over GF(3): f' = 0 as well
        Poly g = Poly.of(3, 1, 0, 1);
        Poly f = g.mul(g).mul(g);
        assertTrue(f.derivative().isZero());
        assertEquals(List.of(new Factor(g, 3)), f.factor().factors());
    }

    @Test
    void mixedMultiplicitiesWithFrobeniusInside() {
        // (x+1)^7 * (x^2+x+3)^2 over GF(7): second part not a p-th power, first part is
        long p = 7;
        Poly a = Poly.of(p, 1, 1);
        Poly b = Poly.of(p, 3, 1, 1);
        Poly f = b.mul(b);
        for (int i = 0; i < 7; i++) {
            f = f.mul(a);
        }
        Factorization fz = f.factor();
        checkStructure(f, fz);
        assertEquals(2, fz.factors().size());
        assertEquals(new Factor(a, 7), fz.factors().get(0));
        assertEquals(new Factor(b, 2), fz.factors().get(1));
    }

    @Test
    void everyMonicIrreducibleFactorsToItself() {
        for (long p : new long[] {3, 5}) {
            for (int deg = 1; deg <= 5; deg++) {
                if (Math.pow(p, deg) > 3500) {
                    continue;
                }
                for (Poly f : PolyTest.allMonic(p, deg)) {
                    Factorization fz = f.factor();
                    checkStructure(f, fz);
                    if (f.isIrreducible()) {
                        assertEquals(List.of(new Factor(f, 1)), fz.factors(), f.toString());
                    } else {
                        assertTrue(fz.factors().size() > 1 || fz.factors().get(0).multiplicity() > 1, f.toString());
                    }
                }
            }
        }
    }

    @Test
    void randomProductsOfKnownIrreducibles() {
        Random r = new Random(77);
        long[] primes = {3, 5, 7, 11};
        for (int t = 0; t < 120; t++) {
            long p = primes[r.nextInt(primes.length)];
            List<Poly> pool = new ArrayList<>();
            for (int deg = 1; deg <= 4; deg++) {
                for (int tries = 0; tries < 40; tries++) {
                    long[] c = new long[deg + 1];
                    c[deg] = 1;
                    for (int i = 0; i < deg; i++) {
                        c[i] = r.nextInt((int) p);
                    }
                    Poly cand = Poly.of(p, c);
                    if (cand.isIrreducible() && !pool.contains(cand)) {
                        pool.add(cand);
                        break;
                    }
                }
            }
            Poly f = Poly.of(p, 1 + r.nextInt((int) p - 1));
            int[] expectMult = new int[pool.size()];
            for (int i = 0; i < pool.size(); i++) {
                expectMult[i] = r.nextInt(3);
                for (int m = 0; m < expectMult[i]; m++) {
                    f = f.mul(pool.get(i));
                }
            }
            Factorization fz = f.factor();
            checkStructure(f, fz);
            int nonzero = 0;
            for (int i = 0; i < pool.size(); i++) {
                if (expectMult[i] > 0) {
                    nonzero++;
                    boolean found = false;
                    for (Factor x : fz.factors()) {
                        if (x.poly().equals(pool.get(i))) {
                            assertEquals(expectMult[i], x.multiplicity());
                            found = true;
                        }
                    }
                    assertTrue(found, "missing " + pool.get(i) + " in " + f);
                }
            }
            assertEquals(nonzero, fz.factors().size());
        }
    }

    @Test
    void rootsMatchBruteForce() {
        Random r = new Random(19);
        for (int t = 0; t < 200; t++) {
            long p = new long[] {3, 5, 7, 11, 13}[r.nextInt(5)];
            Poly f = PolyTest.random(p, 8, r);
            if (f.degree() < 1) {
                continue;
            }
            List<Long> roots = new ArrayList<>();
            for (long x = 0; x < p; x++) {
                if (f.eval(x) == 0) {
                    roots.add(x);
                }
            }
            List<Long> viaFactors = new ArrayList<>();
            for (Factor x : f.factor().factors()) {
                if (x.poly().degree() == 1) {
                    viaFactors.add((p - x.poly().coeff(0)) % p);
                }
            }
            viaFactors.sort(null);
            assertEquals(roots, viaFactors, f.toString());
        }
    }

    @Test
    void multiplicitiesJustAboveP() {
        // over GF(3): multiplicity 4 and 5 are not multiples of p although f' has a (x+1)^3 part.
        long p = 3;
        Poly a = Poly.of(p, 1, 1);
        Poly b = Poly.of(p, 2, 1);
        Poly f4 = a.mul(a).mul(a).mul(a);
        assertEquals(List.of(new Factor(a, 4)), f4.factor().factors());
        Poly f5 = f4.mul(a).mul(b).mul(b);
        assertEquals(List.of(new Factor(a, 5), new Factor(b, 2)), f5.factor().factors());
    }

    @Test
    void largePrimeAndHighDegree() {
        long p = 2147483647L; // 2^31 - 1
        Random r = new Random(4);
        Poly f = Poly.of(p, 1);
        for (int i = 0; i < 6; i++) {
            f = f.mul(Poly.of(p, r.nextInt(Integer.MAX_VALUE - 1) + 1, 1));
        }
        Factorization fz = f.factor();
        assertEquals(6, fz.factors().stream().mapToInt(x -> x.poly().degree() * x.multiplicity()).sum());
        assertEquals(f, product(fz, p));
        for (Factor x : fz.factors()) {
            assertEquals(1, x.poly().degree());
        }
        // degree 12 product of two degree-6 irreducibles over GF(5)
        long q = 5;
        Poly a = firstIrreducible(q, 6, 0);
        Poly b = firstIrreducible(q, 6, 1);
        Factorization two = a.mul(b).factor();
        assertEquals(2, two.factors().size());
        assertEquals(java.util.Set.of(new Factor(a, 1), new Factor(b, 1)), new java.util.HashSet<>(two.factors()));
        assertTrue(two.factors().get(0).poly().compareTo(two.factors().get(1).poly()) < 0, "sorted order");
    }

    static Poly firstIrreducible(long p, int deg, int skip) {
        Random r = new Random(1234 + deg);
        int seen = 0;
        while (true) {
            long[] c = new long[deg + 1];
            c[deg] = 1;
            for (int i = 0; i < deg; i++) {
                c[i] = r.nextInt((int) p);
            }
            Poly cand = Poly.of(p, c);
            if (cand.isIrreducible()) {
                if (seen == skip) {
                    return cand;
                }
                seen++;
            }
        }
    }
}
