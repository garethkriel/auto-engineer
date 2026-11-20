package ae.lp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SimplexTest {
    private static Solution solve(String text) {
        LpModel m = LpParser.parse(text);
        Solution s = Simplex.solve(m);
        assertEquals(List0.NONE, CertificateChecker.check(m, s), "certificate for:\n" + text);
        return s;
    }

    /** Tiny holder so the assertion message reads "no violations". */
    static final class List0 {
        static final java.util.List<String> NONE = java.util.List.of();
    }

    private static Solution.Optimal optimal(String text) {
        Solution s = solve(text);
        assertInstanceOf(Solution.Optimal.class, s, "expected optimal for:\n" + text);
        return (Solution.Optimal) s;
    }

    @Test
    void textbookMaximisation() {
        Solution.Optimal o = optimal("max: 3x + 2y\nx + y <= 4\nx + 3y <= 6\nx <= 3\n");
        assertEquals(Rational.of(11), o.value());
        assertArrayEquals(new Rational[] {Rational.of(3), Rational.of(1)}, o.x());
    }

    @Test
    void minimisationWithGreaterEqualRowsNeedsPhaseOne() {
        // diet problem: min 2a + 3b s.t. a + b >= 4, a + 3b >= 6 -> a=3, b=1, value 9
        Solution.Optimal o = optimal("min: 2a + 3b\na + b >= 4\na + 3b >= 6\n");
        assertEquals(Rational.of(9), o.value());
        assertArrayEquals(new Rational[] {Rational.of(3), Rational.of(1)}, o.x());
    }

    @Test
    void equalityRows() {
        Solution.Optimal o = optimal("max: x + 2y + 3z\nx + y + z = 10\nx - y <= 2\nz <= 4\n");
        // z=4, y as large as possible: x+y=6, x-y<=2 -> x=0,y=6 -> 0+12+12 = 24
        assertEquals(Rational.of(24), o.value());
        assertArrayEquals(new Rational[] {Rational.of(0), Rational.of(6), Rational.of(4)}, o.x());
    }

    @Test
    void fractionalOptimum() {
        Solution.Optimal o = optimal("max: x + y\n2x + y <= 4\nx + 2y <= 4\n");
        assertEquals(Rational.of(8, 3), o.value());
        assertArrayEquals(new Rational[] {Rational.of(4, 3), Rational.of(4, 3)}, o.x());
    }

    @Test
    void negativeRhsRowsAreFlipped() {
        // -x - y <= -2 is x + y >= 2
        Solution.Optimal o = optimal("min: x + 2y\n-x - y <= -2\nx <= 5\n");
        assertEquals(Rational.of(2), o.value());
        assertArrayEquals(new Rational[] {Rational.of(2), Rational.of(0)}, o.x());
    }

    @Test
    void bealeCyclingExampleTerminatesWithBland() {
        Solution.Optimal o = optimal("""
                min: -3/4 x4 + 20 x5 - 1/2 x6 + 6 x7
                1/4 x4 - 8 x5 - x6 + 9 x7 <= 0
                1/2 x4 - 12 x5 - 1/2 x6 + 3 x7 <= 0
                x6 <= 1
                """);
        assertEquals(Rational.of(-5, 4), o.value());
    }

    @Test
    void infeasibleDetectedWithFarkasVector() {
        Solution s = solve("max: x\nx + y <= 2\nx + y >= 5\n");
        Solution.Infeasible inf = assertInstanceOf(Solution.Infeasible.class, s);
        assertEquals(2, inf.farkas().length);
    }

    @Test
    void infeasibleViaConflictingEqualities() {
        assertInstanceOf(Solution.Infeasible.class, solve("min: x\nx + y = 1\nx + y = 2\n"));
    }

    @Test
    void infeasibleBecauseOfNonNegativity() {
        assertInstanceOf(Solution.Infeasible.class, solve("max: x\nx <= -1\n"));
    }

    @Test
    void unboundedDetectedWithRay() {
        Solution s = solve("max: x + y\nx - y <= 2\n");
        Solution.Unbounded u = assertInstanceOf(Solution.Unbounded.class, s);
        assertTrue(u.ray()[1].signum() > 0 || u.ray()[0].signum() > 0);
    }

    @Test
    void unboundedMinimisation() {
        // min -x with only x - y >= 0 -> push x and y up forever
        assertInstanceOf(Solution.Unbounded.class, solve("min: -x\nx - y >= 0\n"));
    }

    @Test
    void noConstraintsBoundedAndUnbounded() {
        Solution.Optimal o = optimal("min: x + y");
        assertEquals(Rational.ZERO, o.value());
        assertInstanceOf(Solution.Unbounded.class, solve("max: x"));
    }

    @Test
    void redundantEqualityRowIsHandled() {
        Solution.Optimal o = optimal("max: x + y\nx + y = 3\n2x + 2y = 6\nx <= 2\n");
        assertEquals(Rational.of(3), o.value());
        assertEquals(3, o.duals().length); // one dual per original row, redundant or not
    }

    @Test
    void infeasibleDespiteRedundantRows() {
        assertInstanceOf(Solution.Infeasible.class, solve("max: x\nx + y = 3\n2x + 2y = 7\n"));
    }

    @Test
    void degenerateVertexWithTiedRatios() {
        Solution.Optimal o = optimal("max: x + y\nx <= 1\ny <= 1\nx + y <= 2\nx + 2y <= 3\n");
        assertEquals(Rational.of(2), o.value());
    }

    @Test
    void zeroObjectiveReturnsAnyFeasiblePoint() {
        Solution.Optimal o = optimal("max: 0 x\nx + y >= 3\nx <= 1\n");
        assertEquals(Rational.ZERO, o.value());
    }

    @Test
    void strongDualityValuesMatchOnKnownDual() {
        // primal: max 4a + 3b s.t. 2a + b <= 10, a + b <= 8, a <= 4 -> a=2,b=6 ... check dual objective
        Solution.Optimal o = optimal("max: 4a + 3b\n2a + b <= 10\na + b <= 8\na <= 4\n");
        assertEquals(Rational.of(26), o.value());
        Rational[] b = {Rational.of(10), Rational.of(8), Rational.of(4)};
        assertEquals(o.value(), Rational.dot(o.duals(), b));
    }
}
