package ae.lp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Random tiny LPs: certificates must verify; optimal values must equal a vertex-enumeration oracle. */
class RandomDifferentialTest {

    /** Builds a random model with n variables and m constraints, small integer data. */
    static LpModel random(Random rnd) {
        int n = 1 + rnd.nextInt(3);
        int m = 1 + rnd.nextInt(4);
        List<String> vars = new ArrayList<>();
        for (int j = 0; j < n; j++) vars.add("v" + j);
        Rational[] obj = new Rational[n];
        for (int j = 0; j < n; j++) obj[j] = Rational.of(rnd.nextInt(7) - 3);
        List<LpModel.Constraint> cs = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            Rational[] a = new Rational[n];
            for (int j = 0; j < n; j++) a[j] = Rational.of(rnd.nextInt(7) - 3);
            LpModel.Op op = LpModel.Op.values()[rnd.nextInt(3)];
            cs.add(new LpModel.Constraint("r" + i, a, op, Rational.of(rnd.nextInt(13) - 3)));
        }
        return new LpModel(rnd.nextBoolean() ? LpModel.Sense.MAX : LpModel.Sense.MIN, vars, obj, cs);
    }

    /** Oracle: best objective over all feasible vertices, or null when there is none. */
    static Rational bestVertex(LpModel m) {
        int n = m.variables().size();
        // candidate "active" hyperplanes: each constraint as equality, and each x_j = 0
        List<Rational[]> rows = new ArrayList<>();
        List<Rational> rhs = new ArrayList<>();
        for (LpModel.Constraint c : m.constraints()) {
            rows.add(c.coef());
            rhs.add(c.rhs());
        }
        for (int j = 0; j < n; j++) {
            Rational[] e = new Rational[n];
            for (int k = 0; k < n; k++) e[k] = k == j ? Rational.ONE : Rational.ZERO;
            rows.add(e);
            rhs.add(Rational.ZERO);
        }
        Rational best = null;
        int total = rows.size();
        for (int mask = 0; mask < (1 << total); mask++) {
            if (Integer.bitCount(mask) != n) continue;
            Rational[][] a = new Rational[n][];
            Rational[] b = new Rational[n];
            int k = 0;
            for (int i = 0; i < total; i++) {
                if ((mask >> i & 1) == 1) {
                    a[k] = rows.get(i).clone();
                    b[k] = rhs.get(i);
                    k++;
                }
            }
            Rational[] x = LinearSolve.solveSquare(a, b);
            if (x == null || !CertificateChecker.isFeasible(m, x)) continue;
            Rational v = Rational.dot(m.objective(), x);
            if (best == null || (m.sense() == LpModel.Sense.MAX ? v.compareTo(best) > 0 : v.compareTo(best) < 0)) {
                best = v;
            }
        }
        return best;
    }

    @Test
    void generatedInstancesAreWellFormed() {
        Random rnd = new Random(1);
        for (int i = 0; i < 50; i++) {
            LpModel m = random(rnd);
            assertFalse(m.variables().isEmpty());
            for (LpModel.Constraint c : m.constraints()) assertEquals(m.variables().size(), c.coef().length);
        }
    }

    @Test
    void solverAgreesWithVertexOracleAndCertificatesVerify() {
        Random rnd = new Random(20261119);
        int optimal = 0, infeasible = 0, unbounded = 0;
        for (int iter = 0; iter < 1500; iter++) {
            LpModel m = random(rnd);
            Solution s = Simplex.solve(m);
            List<String> violations = CertificateChecker.check(m, s);
            assertEquals(List.of(), violations, "iteration " + iter + " model " + m);
            Rational best = bestVertex(m);
            switch (s) {
                case Solution.Optimal o -> {
                    optimal++;
                    assertNotNull(best, "iteration " + iter + ": solver optimal but no feasible vertex: " + m);
                    assertEquals(best, o.value(), "iteration " + iter + " " + m);
                }
                case Solution.Infeasible i -> {
                    infeasible++;
                    assertNull(best, "iteration " + iter + ": solver infeasible but vertex exists: " + m);
                }
                case Solution.Unbounded u -> {
                    unbounded++;
                    assertNotNull(best, "iteration " + iter + ": unbounded needs a feasible vertex: " + m);
                }
            }
        }
        // measured mix guards against a generator that only produces one outcome
        assertTrue(optimal > 150, "optimal=" + optimal);
        assertTrue(infeasible > 150, "infeasible=" + infeasible);
        assertTrue(unbounded > 50, "unbounded=" + unbounded);
    }
}
