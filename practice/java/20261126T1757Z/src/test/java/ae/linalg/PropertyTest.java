package ae.linalg;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Seeded randomised cross-checks; every routine is compared against an independent oracle. */
class PropertyTest {
    static long[][] random(Random rnd, int rows, int cols, int bound) {
        long[][] a = new long[rows][cols];
        for (long[] row : a) {
            for (int j = 0; j < cols; j++) {
                row[j] = rnd.nextInt(2 * bound + 1) - bound;
            }
        }
        return a;
    }

    /** Rank-deficient matrix: some rows are integer combinations of earlier rows. */
    static long[][] deficient(Random rnd, int n) {
        long[][] a = random(rnd, n, n, 3);
        for (int i = 1; i < n; i++) {
            if (rnd.nextInt(3) == 0) {
                int j = rnd.nextInt(i);
                long k = rnd.nextInt(5) - 2;
                for (int c = 0; c < n; c++) {
                    a[i][c] = k * a[j][c];
                }
            }
        }
        return a;
    }

    /** Laplace expansion along row 0: exponential, so only for n <= 5. */
    static BigInteger cofactor(long[][] a) {
        int n = a.length;
        if (n == 1) {
            return BigInteger.valueOf(a[0][0]);
        }
        BigInteger sum = BigInteger.ZERO;
        for (int c = 0; c < n; c++) {
            long[][] minor = new long[n - 1][n - 1];
            for (int i = 1; i < n; i++) {
                int cc = 0;
                for (int j = 0; j < n; j++) {
                    if (j != c) {
                        minor[i - 1][cc++] = a[i][j];
                    }
                }
            }
            BigInteger term = BigInteger.valueOf(a[0][c]).multiply(cofactor(minor));
            sum = (c % 2 == 0) ? sum.add(term) : sum.subtract(term);
        }
        return sum;
    }

    static Rational[] vec(Random rnd, int n) {
        Rational[] v = new Rational[n];
        for (int i = 0; i < n; i++) {
            v[i] = Rational.of(rnd.nextInt(9) - 4);
        }
        return v;
    }

    static boolean isZero(Rational[] v) {
        for (Rational r : v) {
            if (!r.isZero()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void generatorsProduceSingularAndRegularMatrices() {
        Random rnd = new Random(1);
        int singular = 0;
        int regular = 0;
        for (int t = 0; t < 300; t++) {
            if (cofactor(deficient(rnd, 4)).signum() == 0) {
                singular++;
            } else {
                regular++;
            }
        }
        assertTrue(singular >= 30, "singular=" + singular);
        assertTrue(regular >= 30, "regular=" + regular);
    }

    @Test
    void determinantThreeWays() {
        Random rnd = new Random(2);
        for (int t = 0; t < 300; t++) {
            int n = 1 + rnd.nextInt(5);
            long[][] a = t % 2 == 0 ? random(rnd, n, n, 4) : deficient(rnd, n);
            BigInteger expected = cofactor(a);
            Matrix m = Matrix.of(a);
            Rational gauss = Elimination.determinant(m);
            assertEquals(Rational.of(expected.longValueExact()), gauss, "gauss " + m);
            assertEquals(expected, Bareiss.determinant(a), "bareiss " + m);
            assertEquals(gauss, Lu.decompose(m).determinant(), "lu " + m);
            assertEquals(gauss, Elimination.determinant(m.transpose()), "transpose " + m);
        }
    }

    @Test
    void determinantIsMultiplicative() {
        Random rnd = new Random(3);
        for (int t = 0; t < 100; t++) {
            int n = 2 + rnd.nextInt(3);
            Matrix a = Matrix.of(random(rnd, n, n, 3));
            Matrix b = Matrix.of(random(rnd, n, n, 3));
            assertEquals(Elimination.determinant(a).mul(Elimination.determinant(b)), Elimination.determinant(a.times(b)));
        }
    }

    @Test
    void inverseExistsExactlyWhenDeterminantNonZero() {
        Random rnd = new Random(4);
        int inverted = 0;
        int refused = 0;
        for (int t = 0; t < 300; t++) {
            int n = 1 + rnd.nextInt(5);
            Matrix a = Matrix.of(t % 2 == 0 ? random(rnd, n, n, 3) : deficient(rnd, n));
            if (Elimination.determinant(a).isZero()) {
                assertThrows(SingularMatrixException.class, () -> Elimination.inverse(a), a.toString());
                refused++;
            } else {
                Matrix inv = Elimination.inverse(a);
                assertEquals(Matrix.identity(n), a.times(inv), a.toString());
                assertEquals(Matrix.identity(n), inv.times(a), a.toString());
                inverted++;
            }
        }
        assertTrue(inverted > 30 && refused > 30, inverted + "/" + refused);
    }

    @Test
    void rankNullityAndNullspaceVectors() {
        Random rnd = new Random(5);
        for (int t = 0; t < 300; t++) {
            int r = 1 + rnd.nextInt(5);
            int c = 1 + rnd.nextInt(5);
            long[][] raw = random(rnd, r, c, 2);
            if (t % 3 == 0 && r > 1) {
                raw[r - 1] = raw[0].clone();
            }
            Matrix a = Matrix.of(raw);
            int rank = Elimination.rank(a);
            List<Rational[]> ns = Elimination.nullspace(a);
            assertEquals(c, rank + ns.size(), a.toString());
            assertEquals(rank, Elimination.rank(a.transpose()), "rank(A)=rank(A^T) " + a);
            assertEquals(rank, Elimination.columnSpaceBasis(a).size());
            for (Rational[] x : ns) {
                assertTrue(isZero(a.apply(x)), "A*n=0 for " + a);
                assertFalse(isZero(x));
            }
            // the basis is independent: its own rank equals its size
            if (!ns.isEmpty()) {
                Rational[][] rows = ns.toArray(new Rational[0][]);
                assertEquals(ns.size(), Elimination.rank(Matrix.ofRational(rows)), a.toString());
            }
        }
    }

    @Test
    void solveClassifiesAndVerifiesAgainstOracle() {
        Random rnd = new Random(6);
        int unique = 0;
        int infinite = 0;
        int inconsistent = 0;
        for (int t = 0; t < 400; t++) {
            int r = 1 + rnd.nextInt(4);
            int c = 1 + rnd.nextInt(4);
            Matrix a = Matrix.of(random(rnd, r, c, 2));
            Rational[] b = t % 2 == 0 ? a.apply(vec(rnd, c)) : vec(rnd, r);
            Solution s = Elimination.solve(a, b);
            int rank = Elimination.rank(a);
            // consistency oracle: rank([A|b]) == rank(A)
            Rational[][] aug = new Rational[r][c + 1];
            for (int i = 0; i < r; i++) {
                System.arraycopy(a.row(i), 0, aug[i], 0, c);
                aug[i][c] = b[i];
            }
            boolean consistent = Elimination.rank(Matrix.ofRational(aug)) == rank;
            switch (s) {
                case Solution.Unique u -> {
                    assertTrue(consistent && rank == c, a.toString());
                    assertArrayEquals(b, a.apply(u.x()));
                    unique++;
                }
                case Solution.Infinite inf -> {
                    assertTrue(consistent && rank < c, a.toString());
                    assertArrayEquals(b, a.apply(inf.particular()));
                    assertEquals(c - rank, inf.nullBasis().size());
                    for (Rational[] n : inf.nullBasis()) {
                        assertTrue(isZero(a.apply(n)));
                    }
                    infinite++;
                }
                case Solution.Inconsistent i -> {
                    assertFalse(consistent, a.toString());
                    inconsistent++;
                }
            }
        }
        assertTrue(unique >= 20 && infinite >= 20 && inconsistent >= 20, unique + "/" + infinite + "/" + inconsistent);
    }

    @Test
    void luReconstructsAndSolves() {
        Random rnd = new Random(7);
        for (int t = 0; t < 300; t++) {
            int n = 1 + rnd.nextInt(5);
            Matrix a = Matrix.of(t % 2 == 0 ? random(rnd, n, n, 4) : deficient(rnd, n));
            Lu lu = Lu.decompose(a);
            assertEquals(lu.p().times(a), lu.l().times(lu.u()), a.toString());
            for (int i = 0; i < n; i++) {
                assertEquals(Rational.ONE, lu.l().get(i, i));
                for (int j = i + 1; j < n; j++) {
                    assertEquals(Rational.ZERO, lu.l().get(i, j));
                }
                for (int j = 0; j < i; j++) {
                    assertEquals(Rational.ZERO, lu.u().get(i, j));
                }
            }
            if (!Elimination.determinant(a).isZero()) {
                Rational[] b = vec(rnd, n);
                assertArrayEquals(b, a.apply(lu.solve(b)), a.toString());
            }
        }
    }

    /** Evaluate a polynomial at a matrix with Horner's rule. */
    static Matrix evalAt(Rational[] coef, Matrix a) {
        Matrix acc = Matrix.zero(a.rows(), a.cols());
        for (int k = coef.length - 1; k >= 0; k--) {
            acc = acc.times(a).plus(Matrix.identity(a.rows()).times(coef[k]));
        }
        return acc;
    }

    @Test
    void cayleyHamiltonAndTraceDeterminantCoefficients() {
        Random rnd = new Random(8);
        for (int t = 0; t < 200; t++) {
            int n = 1 + rnd.nextInt(5);
            Matrix a = Matrix.of(random(rnd, n, n, 3));
            Rational[] c = CharPoly.coefficients(a);
            assertEquals(n + 1, c.length);
            assertEquals(Rational.ONE, c[n]);
            assertEquals(a.trace().negate(), c[n - 1], "trace " + a);
            Rational sign = n % 2 == 0 ? Rational.ONE : Rational.of(-1);
            assertEquals(Elimination.determinant(a).mul(sign), c[0], "det " + a);
            // Cayley-Hamilton: p(A) = 0
            assertEquals(Matrix.zero(n, n), evalAt(c, a), a.toString());
        }
    }

    @Test
    void eigenvectorsSatisfyDefinitionAndMultiplicitiesSumToDegree() {
        Random rnd = new Random(9);
        int withEigenvalues = 0;
        for (int t = 0; t < 200; t++) {
            int n = 2 + rnd.nextInt(3);
            // similar-to-diagonal-ish: triangular matrix so that eigenvalues are rational by construction
            long[][] raw = random(rnd, n, n, 2);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < i; j++) {
                    raw[i][j] = 0;
                }
            }
            Matrix a = Matrix.of(raw);
            Map<Rational, Integer> ev = CharPoly.rationalEigenvalues(a);
            int total = 0;
            for (Map.Entry<Rational, Integer> e : ev.entrySet()) {
                total += e.getValue();
                List<Rational[]> basis = CharPoly.eigenspace(a, e.getKey());
                assertFalse(basis.isEmpty(), a + " lambda=" + e.getKey());
                assertTrue(basis.size() <= e.getValue(), "geometric <= algebraic");
                for (Rational[] x : basis) {
                    Rational[] ax = a.apply(x);
                    for (int i = 0; i < n; i++) {
                        assertEquals(e.getKey().mul(x[i]), ax[i], a + " lambda=" + e.getKey());
                    }
                }
            }
            // triangular: the eigenvalues are the diagonal, all rational
            assertEquals(n, total, a.toString());
            Map<Rational, Integer> diag = new java.util.TreeMap<>();
            for (int i = 0; i < n; i++) {
                diag.merge(a.get(i, i), 1, Integer::sum);
            }
            assertEquals(diag, new java.util.TreeMap<>(ev));
            withEigenvalues++;
        }
        assertEquals(200, withEigenvalues);
    }

    @Test
    void gramSchmidtIsOrthogonalAndSpansTheSame() {
        Random rnd = new Random(10);
        for (int t = 0; t < 200; t++) {
            int dim = 2 + rnd.nextInt(3);
            int count = 1 + rnd.nextInt(5);
            List<Rational[]> in = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                in.add(vec(rnd, dim));
            }
            List<Rational[]> out = GramSchmidt.orthogonalize(in);
            for (int i = 0; i < out.size(); i++) {
                assertFalse(isZero(out.get(i)));
                for (int j = i + 1; j < out.size(); j++) {
                    assertEquals(Rational.ZERO, GramSchmidt.dot(out.get(i), out.get(j)));
                }
            }
            Matrix inM = Matrix.ofRational(in.toArray(new Rational[0][]));
            assertEquals(Elimination.rank(inM), out.size());
            // every input vector is reproduced by projecting onto the output basis
            for (Rational[] x : in) {
                assertArrayEquals(x, GramSchmidt.project(x, out));
            }
        }
    }
}
