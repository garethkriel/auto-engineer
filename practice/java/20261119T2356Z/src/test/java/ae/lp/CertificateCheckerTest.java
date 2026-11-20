package ae.lp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Kill checks: the checker must reject each way a certificate can be wrong. */
class CertificateCheckerTest {
    private static final String LP = "max: 3x + 2y\nx + y <= 4\nx + 3y <= 6\nx <= 3\n";

    private static Rational[] q(long... v) {
        Rational[] out = new Rational[v.length];
        for (int i = 0; i < v.length; i++) out[i] = Rational.of(v[i]);
        return out;
    }

    private static void rejected(LpModel m, Solution s, String fragment) {
        List<String> v = CertificateChecker.check(m, s);
        assertFalse(v.isEmpty(), "expected a violation containing: " + fragment);
        assertTrue(v.stream().anyMatch(x -> x.contains(fragment)), v + " should mention " + fragment);
    }

    @Test
    void acceptsGenuineOptimalCertificate() {
        LpModel m = LpParser.parse(LP);
        Solution.Optimal o = (Solution.Optimal) Simplex.solve(m);
        assertEquals(List.of(), CertificateChecker.check(m, o));
    }

    @Test
    void rejectsWrongObjectiveValue() {
        LpModel m = LpParser.parse(LP);
        Solution.Optimal o = (Solution.Optimal) Simplex.solve(m);
        rejected(m, new Solution.Optimal(Rational.of(12), o.x(), o.duals()), "objective");
    }

    @Test
    void rejectsInfeasiblePrimalPoint() {
        LpModel m = LpParser.parse(LP);
        Solution.Optimal o = (Solution.Optimal) Simplex.solve(m);
        rejected(m, new Solution.Optimal(Rational.of(12), q(4, 0), o.duals()), "constraint");
    }

    @Test
    void rejectsNegativePrimalVariable() {
        LpModel m = LpParser.parse(LP);
        Solution.Optimal o = (Solution.Optimal) Simplex.solve(m);
        rejected(m, new Solution.Optimal(Rational.of(3 * 3 - 2), q(3, -1), o.duals()), "negative");
    }

    @Test
    void rejectsSuboptimalPointEvenWithFeasiblePrimal() {
        LpModel m = LpParser.parse(LP);
        Solution.Optimal o = (Solution.Optimal) Simplex.solve(m);
        // x=2,y=0 is feasible with value 6 but the dual does not certify it
        rejected(m, new Solution.Optimal(Rational.of(6), q(2, 0), o.duals()), "dual");
    }

    @Test
    void rejectsDualWithWrongSign() {
        LpModel m = LpParser.parse(LP);
        Solution.Optimal o = (Solution.Optimal) Simplex.solve(m);
        Rational[] bad = o.duals().clone();
        bad[0] = Rational.of(-1); // '<=' rows need y >= 0
        rejected(m, new Solution.Optimal(o.value(), o.x(), bad), "sign");
    }

    @Test
    void rejectsDualWithWrongLength() {
        LpModel m = LpParser.parse(LP);
        Solution.Optimal o = (Solution.Optimal) Simplex.solve(m);
        rejected(m, new Solution.Optimal(o.value(), o.x(), q(1)), "length");
    }

    @Test
    void rejectsFakeFarkasVector() {
        LpModel m = LpParser.parse("max: x\nx + y <= 2\nx + y >= 5\n");
        // y=(1,-1): y^T A = (0,0) >= 0 and y^T b = 2 - 5 = -3 < 0 is the real certificate
        assertEquals(List.of(), CertificateChecker.check(m, new Solution.Infeasible(q(1, -1))));
        rejected(m, new Solution.Infeasible(q(0, 0)), "Farkas");
        rejected(m, new Solution.Infeasible(q(-1, 1)), "sign");
        rejected(m, new Solution.Infeasible(q(1, 0)), "Farkas"); // y^T b = 2 >= 0
    }

    @Test
    void feasibleModelCannotBeCertifiedInfeasible() {
        LpModel m = LpParser.parse("max: x\nx + y <= 2\n");
        rejected(m, new Solution.Infeasible(q(1)), "Farkas");
    }

    @Test
    void unboundedCertificateChecks() {
        LpModel m = LpParser.parse("max: x + y\nx - y <= 2\n");
        assertEquals(List.of(), CertificateChecker.check(m, new Solution.Unbounded(q(0, 0), q(1, 1))));
        rejected(m, new Solution.Unbounded(q(0, 0), q(1, 0)), "ray");            // A d = 1 > 0
        rejected(m, new Solution.Unbounded(q(0, 0), q(0, 0)), "improv");          // c.d = 0
        rejected(m, new Solution.Unbounded(q(0, 0), q(-1, -2)), "negative");      // d < 0
        rejected(m, new Solution.Unbounded(q(5, 0), q(1, 1)), "point");           // x0 infeasible
    }

    @Test
    void unboundedMinimisationNeedsDecreasingRay() {
        LpModel m = LpParser.parse("min: -x\nx - y >= 0\n");
        assertEquals(List.of(), CertificateChecker.check(m, new Solution.Unbounded(q(0, 0), q(1, 1))));
        rejected(m, new Solution.Unbounded(q(0, 0), q(0, 1)), "improv");
    }

    @Test
    void equalityRayMustBeHomogeneous() {
        LpModel m = LpParser.parse("max: x\nx - y = 0\n");
        assertEquals(List.of(), CertificateChecker.check(m, new Solution.Unbounded(q(0, 0), q(1, 1))));
        rejected(m, new Solution.Unbounded(q(0, 0), q(2, 1)), "ray");
    }
}
