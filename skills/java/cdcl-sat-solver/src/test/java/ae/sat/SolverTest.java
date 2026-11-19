package ae.sat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SolverTest {

    private static Cnf cnf(int vars, int[]... clauses) {
        return new Cnf(vars, List.of(clauses));
    }

    /** Solves and verifies the answer with independent code; returns the result. */
    private static Result solveChecked(Cnf cnf) {
        Result r = new Solver(cnf).solve();
        if (r.status() == Status.SAT) {
            assertTrue(ModelChecker.satisfies(cnf, r.model()), "model must satisfy the formula");
        } else {
            assertEquals(Status.UNSAT, r.status());
            assertTrue(RupChecker.check(cnf, r.proof()), "proof must pass the RUP checker");
        }
        return r;
    }

    @Test
    void emptyFormulaIsSat() {
        Result r = solveChecked(cnf(3));
        assertEquals(Status.SAT, r.status());
        assertEquals(4, r.model().length);
    }

    @Test
    void emptyClauseIsUnsatWithTrivialProof() {
        Result r = solveChecked(cnf(2, new int[] {1, 2}, new int[] {}));
        assertEquals(Status.UNSAT, r.status());
        assertEquals(1, r.proof().size());
        assertEquals(0, r.proof().get(0).length);
    }

    @Test
    void contradictoryUnitsAreUnsat() {
        assertEquals(Status.UNSAT, solveChecked(cnf(1, new int[] {1}, new int[] {-1})).status());
    }

    @Test
    void unitChainForcesAllValues() {
        // 1, 1->2, 2->3, 3->-4 : the only model is 1,2,3 true and 4 false.
        Result r = solveChecked(cnf(4, new int[] {1}, new int[] {-1, 2}, new int[] {-2, 3}, new int[] {-3, -4}));
        assertEquals(Status.SAT, r.status());
        assertTrue(r.model()[1] && r.model()[2] && r.model()[3]);
        assertFalse(r.model()[4]);
        assertEquals(0, r.conflicts());
        assertEquals(0, r.decisions(), "everything is forced by propagation");
    }

    @Test
    void tautologiesAndDuplicateLiteralsAreHandled() {
        Result r = solveChecked(cnf(2, new int[] {1, -1}, new int[] {2, 2, 2}, new int[] {-2, -2, 1}));
        assertEquals(Status.SAT, r.status());
        assertTrue(r.model()[1] && r.model()[2]);
    }

    @Test
    void allTwoVariableClausesUnsat() {
        Result r = solveChecked(cnf(2, new int[] {1, 2}, new int[] {1, -2}, new int[] {-1, 2}, new int[] {-1, -2}));
        assertEquals(Status.UNSAT, r.status());
        assertTrue(r.conflicts() >= 1);
    }

    @Test
    void differentialAgainstBruteForceOnRandom3Sat() {
        int sat = 0;
        int unsat = 0;
        for (int seed = 0; seed < 400; seed++) {
            int vars = 6 + seed % 9; // 6..14
            double ratio = seed % 2 == 0 ? 4.3 : 5.5; // even seeds near the threshold, odd seeds mostly UNSAT
            int clauses = (int) Math.round(vars * ratio);
            Cnf f = Encodings.random3Sat(vars, clauses, seed);
            boolean expected = bruteForceSat(f);
            Result r = solveChecked(f);
            assertEquals(expected, r.status() == Status.SAT, "seed " + seed + " vars " + vars);
            if (expected) {
                sat++;
            } else {
                unsat++;
            }
        }
        assertTrue(sat >= 100 && unsat >= 100, "need both kinds, got sat=" + sat + " unsat=" + unsat);
    }

    @Test
    void pigeonholeIsUnsatAndSatWhenEnoughHoles() {
        for (int n = 1; n <= 6; n++) {
            assertEquals(Status.UNSAT, solveChecked(Encodings.pigeonhole(n + 1, n)).status(), "n=" + n);
            assertEquals(Status.SAT, solveChecked(Encodings.pigeonhole(n, n)).status(), "n=" + n);
        }
    }

    @Test
    void queensKnownAnswers() {
        int[] sat = {1, 4, 5, 6, 7, 8, 9, 10};
        for (int n : sat) {
            Result r = solveChecked(Encodings.queens(n));
            assertEquals(Status.SAT, r.status(), "n=" + n);
            assertTrue(validQueens(n, r.model()), "board for n=" + n);
        }
        assertEquals(Status.UNSAT, solveChecked(Encodings.queens(2)).status());
        assertEquals(Status.UNSAT, solveChecked(Encodings.queens(3)).status());
    }

    @Test
    void graphColouring() {
        // Petersen graph: outer 0-4 cycle, inner 5-9 pentagram, spokes i - i+5.
        List<int[]> petersen = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            petersen.add(new int[] {i, (i + 1) % 5});
            petersen.add(new int[] {5 + i, 5 + (i + 2) % 5});
            petersen.add(new int[] {i, i + 5});
        }
        assertEquals(15, petersen.size());
        assertEquals(Status.SAT, solveChecked(Encodings.colouring(10, petersen, 3)).status());
        assertEquals(Status.UNSAT, solveChecked(Encodings.colouring(10, petersen, 2)).status());
        List<int[]> k4 = List.of(new int[] {0, 1}, new int[] {0, 2}, new int[] {0, 3}, new int[] {1, 2},
                new int[] {1, 3}, new int[] {2, 3});
        assertEquals(Status.UNSAT, solveChecked(Encodings.colouring(4, k4, 3)).status());
        assertEquals(Status.SAT, solveChecked(Encodings.colouring(4, k4, 4)).status());
    }

    @Test
    void conflictLimitGivesUnknown() {
        Cnf php = Encodings.pigeonhole(8, 7);
        Result r = new Solver(php).solve(5);
        assertEquals(Status.UNKNOWN, r.status());
        assertTrue(r.conflicts() >= 5 && r.conflicts() <= 6, "conflicts " + r.conflicts());
        assertThrows(IllegalArgumentException.class, () -> new Solver(php).solve(0));
    }

    @Test
    void solverIsDeterministicAndFormulaIsNotMutated() {
        Cnf f = Encodings.random3Sat(40, 170, 7);
        String before = Dimacs.write(f);
        Result a = new Solver(f).solve();
        Result b = new Solver(f).solve();
        assertEquals(a.status(), b.status());
        assertEquals(a.conflicts(), b.conflicts());
        assertEquals(a.decisions(), b.decisions());
        assertArrayEquals(a.model(), b.model());
        assertEquals(before, Dimacs.write(f));
    }

    @Test
    void mediumRandomInstancesAreCheckedBothWays() {
        int sat = 0;
        int unsat = 0;
        for (int seed = 100; seed < 130; seed++) {
            Cnf f = Encodings.random3Sat(60, 258, seed);
            Result r = solveChecked(f);
            if (r.status() == Status.SAT) {
                sat++;
            } else {
                unsat++;
            }
        }
        assertTrue(sat > 0 && unsat > 0, "sat=" + sat + " unsat=" + unsat);
    }

    @Test
    void largeSatisfiableInstanceIsFast() {
        // Under-constrained (ratio 3.0): must solve quickly.
        Cnf f = Encodings.random3Sat(3000, 9000, 1);
        long t0 = System.nanoTime();
        Result r = new Solver(f).solve();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(Status.SAT, r.status());
        assertTrue(ModelChecker.satisfies(f, r.model()));
        assertTrue(ms < 10_000, "took " + ms + " ms");
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static boolean bruteForceSat(Cnf f) {
        int n = f.numVars();
        for (long mask = 0; mask < (1L << n); mask++) {
            boolean[] m = new boolean[n + 1];
            for (int v = 1; v <= n; v++) {
                m[v] = ((mask >> (v - 1)) & 1) != 0;
            }
            if (ModelChecker.satisfies(f, m)) {
                return true;
            }
        }
        return false;
    }

    /** Variable (r*n+c+1) means a queen on row r, column c. */
    private static boolean validQueens(int n, boolean[] model) {
        int[] col = new int[n];
        for (int r = 0; r < n; r++) {
            int count = 0;
            for (int c = 0; c < n; c++) {
                if (model[r * n + c + 1]) {
                    col[r] = c;
                    count++;
                }
            }
            if (count != 1) {
                return false;
            }
        }
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                if (col[a] == col[b] || Math.abs(col[a] - col[b]) == b - a) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    void randomGeneratorIsSeededAndWellFormed() {
        Cnf a = Encodings.random3Sat(10, 20, 5);
        Cnf b = Encodings.random3Sat(10, 20, 5);
        assertEquals(Dimacs.write(a), Dimacs.write(b));
        assertNotEquals(Dimacs.write(a), Dimacs.write(Encodings.random3Sat(10, 20, 6)));
        for (int[] c : a.clauses()) {
            assertEquals(3, c.length);
            assertEquals(3, java.util.Arrays.stream(c).map(Math::abs).distinct().count(), "distinct variables");
        }
    }
}
