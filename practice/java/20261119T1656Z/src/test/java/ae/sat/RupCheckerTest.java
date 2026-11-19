package ae.sat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RupCheckerTest {

    private static final int[] EMPTY = new int[0];

    private static Cnf cnf(int vars, int[]... clauses) {
        return new Cnf(vars, List.of(clauses));
    }

    @Test
    void acceptsHandWrittenProof() {
        // (1|2)(1|-2)(-1|2)(-1|-2): learn (1) then () .
        Cnf f = cnf(2, new int[] {1, 2}, new int[] {1, -2}, new int[] {-1, 2}, new int[] {-1, -2});
        assertTrue(RupChecker.check(f, List.of(new int[] {1}, EMPTY)));
    }

    @Test
    void emptyClauseAloneWorksWhenPropagationAlreadyConflicts() {
        Cnf f = cnf(1, new int[] {1}, new int[] {-1});
        assertTrue(RupChecker.check(f, List.of(EMPTY)));
    }

    @Test
    void rejectsClauseNotImplied() {
        Cnf f = cnf(3, new int[] {1, 2}, new int[] {-1, 3});
        assertFalse(RupChecker.check(f, List.of(new int[] {3}, EMPTY)), "(3) is not implied by the formula");
    }

    @Test
    void rejectsProofWithoutEmptyClause() {
        Cnf f = cnf(2, new int[] {1, 2}, new int[] {1, -2}, new int[] {-1, 2}, new int[] {-1, -2});
        assertFalse(RupChecker.check(f, List.of(new int[] {1})));
        assertFalse(RupChecker.check(f, List.of()));
    }

    @Test
    void rejectsEmptyClauseOnSatisfiableFormula() {
        Cnf f = cnf(2, new int[] {1, 2});
        assertFalse(RupChecker.check(f, List.of(EMPTY)));
    }

    @Test
    void realProofsAreSoundAndCorruptionIsCaught() {
        Cnf php = Encodings.pigeonhole(6, 5);
        Result r = new Solver(php).solve();
        assertEquals(Status.UNSAT, r.status());
        assertTrue(r.proof().size() > 5, "a pigeonhole proof needs learned clauses, got " + r.proof().size());
        assertTrue(RupChecker.check(php, r.proof()));

        // Drop every learned clause: the bare empty clause cannot be derived by propagation alone.
        assertFalse(RupChecker.check(php, List.of(EMPTY)));

        // Cut the proof short.
        List<int[]> truncated = new ArrayList<>(r.proof().subList(0, r.proof().size() - 1));
        assertFalse(RupChecker.check(php, truncated));

        // Weaken the first learned clause by dropping a literal: it is no longer implied.
        int[] first = r.proof().get(0);
        assertTrue(first.length >= 2, "first learned clause should be non-unit for this check");
        List<int[]> tampered = new ArrayList<>(r.proof());
        tampered.set(0, java.util.Arrays.copyOf(first, first.length - 1));
        assertFalse(RupChecker.check(php, tampered));
    }
}
