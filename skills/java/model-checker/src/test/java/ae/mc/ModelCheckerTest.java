package ae.mc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ModelCheckerTest {

    static <S> void assertValidTrace(Model<S> m, List<Step<S>> trace) {
        assertFalse(trace.isEmpty());
        assertEquals("init", trace.get(0).label());
        assertTrue(m.initial().contains(trace.get(0).state()), "first state must be initial");
        for (int i = 1; i < trace.size(); i++) {
            S prev = trace.get(i - 1).state();
            Step<S> cur = trace.get(i);
            boolean ok = false;
            for (Move<S> mv : m.moves(prev)) {
                if (mv.label().equals(cur.label()) && mv.target().equals(cur.state())) ok = true;
            }
            assertTrue(ok, "step " + i + " is not a real move: " + cur);
        }
    }

    static Map<String, Predicate<Examples.Jugs>> never(int amount) {
        Map<String, Predicate<Examples.Jugs>> inv = new LinkedHashMap<>();
        inv.put("no-" + amount, j -> j.a() != amount && j.b() != amount);
        return inv;
    }

    @Test
    void jugsSolutionIsShortestSixSteps() {
        Model<Examples.Jugs> m = Examples.jugs(3, 5);
        Verdict<Examples.Jugs> v = ModelChecker.checkSafety(m, never(4), s -> false, 1000);
        Verdict.InvariantViolated<Examples.Jugs> iv = assertInstanceOf(Verdict.InvariantViolated.class, v);
        assertEquals("no-4", iv.name());
        assertEquals(7, iv.trace().size()); // init + 6 moves
        assertValidTrace(m, iv.trace());
    }

    @Test
    void unreachableAmountHoldsWithSixStates() {
        Model<Examples.Jugs> m = Examples.jugs(2, 4);
        Verdict<Examples.Jugs> v = ModelChecker.checkSafety(m, never(3), s -> true, 1000);
        Verdict.Holds<Examples.Jugs> h = assertInstanceOf(Verdict.Holds.class, v);
        assertEquals(6, h.states());
    }

    @Test
    void initialStateViolationGivesSingleStepTrace() {
        Model<Examples.Jugs> m = Examples.jugs(3, 5);
        Map<String, Predicate<Examples.Jugs>> inv = new LinkedHashMap<>();
        inv.put("nonempty", j -> j.a() + j.b() > 0);
        Verdict.InvariantViolated<Examples.Jugs> iv =
                assertInstanceOf(Verdict.InvariantViolated.class, ModelChecker.checkSafety(m, inv, s -> true, 100));
        assertEquals(1, iv.trace().size());
    }

    @Test
    void invariantsCheckedInMapOrder() {
        Model<Examples.Jugs> m = Examples.jugs(3, 5);
        Map<String, Predicate<Examples.Jugs>> inv = new LinkedHashMap<>();
        inv.put("first", j -> j.a() + j.b() > 0);
        inv.put("second", j -> j.a() + j.b() > 0);
        Verdict.InvariantViolated<Examples.Jugs> iv =
                assertInstanceOf(Verdict.InvariantViolated.class, ModelChecker.checkSafety(m, inv, s -> true, 100));
        assertEquals("first", iv.name());
    }

    @Test
    void maxStatesGivesIncomplete() {
        Model<Examples.Jugs> m = Examples.jugs(3, 5);
        Verdict<Examples.Jugs> v = ModelChecker.checkSafety(m, never(99), s -> true, 5);
        assertInstanceOf(Verdict.Incomplete.class, v);
    }

    @Test
    void petersonIsMutuallyExclusiveAndDeadlockFree() {
        Verdict<Examples.Peterson> v = ModelChecker.checkSafety(
                Examples.peterson(), Map.of("mutex", Examples.Peterson::mutex), s -> false, 100_000);
        assertInstanceOf(Verdict.Holds.class, v);
    }

    @Test
    void checkThenSetViolatesMutexWithShortestTrace() {
        Model<Examples.Peterson> m = Examples.checkThenSet();
        Verdict.InvariantViolated<Examples.Peterson> iv = assertInstanceOf(
                Verdict.InvariantViolated.class,
                ModelChecker.checkSafety(m, Map.of("mutex", Examples.Peterson::mutex), s -> false, 100_000));
        assertValidTrace(m, iv.trace());
        // each process needs 2 steps to enter (check, then set) wait: check passes, set flag, enter = 3 steps
        // p0: check, set, enter (3) + p1: check, set, enter (3) = 6 moves + init
        assertEquals(7, iv.trace().size());
    }

    @Test
    void flagOnlyMutexSafeButDeadlocks() {
        Model<Examples.Peterson> m = Examples.flagOnly();
        Verdict.Deadlock<Examples.Peterson> d = assertInstanceOf(
                Verdict.Deadlock.class,
                ModelChecker.checkSafety(m, Map.of("mutex", Examples.Peterson::mutex), s -> false, 100_000));
        assertValidTrace(m, d.trace());
        assertEquals(0, m.moves(d.trace().get(d.trace().size() - 1).state()).size());
    }

    @Test
    void philosophersDeadlockWhenNaiveAndNotWhenOrdered() {
        Model<Examples.Table> naive = Examples.philosophers(3, false);
        Verdict.Deadlock<Examples.Table> d = assertInstanceOf(
                Verdict.Deadlock.class, ModelChecker.checkSafety(naive, Map.of(), s -> false, 100_000));
        assertValidTrace(naive, d.trace());
        assertEquals(4, d.trace().size()); // everyone picks up their first fork: 3 moves
        Verdict<Examples.Table> ok = ModelChecker.checkSafety(
                Examples.philosophers(3, true), Map.of("forks", Examples.Table::forksConsistent), s -> false, 100_000);
        assertInstanceOf(Verdict.Holds.class, ok);
    }

    @Test
    void petersonLivenessFailsWithoutFairnessWithValidLasso() {
        Model<Examples.Peterson> m = Examples.peterson();
        Verdict.LivenessViolated<Examples.Peterson> lv = assertInstanceOf(
                Verdict.LivenessViolated.class,
                ModelChecker.checkEventually(m, "p0-enters", s -> s.pc0() == Examples.Peterson.CRIT, 100_000));
        assertValidTrace(m, lv.prefix());
        assertFalse(lv.cycle().isEmpty());
        List<Step<Examples.Peterson>> full = new ArrayList<>(lv.prefix());
        full.addAll(lv.cycle());
        assertValidTrace(m, full);
        assertEquals(lv.prefix().get(lv.prefix().size() - 1).state(), lv.cycle().get(lv.cycle().size() - 1).state());
        for (Step<Examples.Peterson> s : full) assertNotEquals(Examples.Peterson.CRIT, s.state().pc0());
    }

    @Test
    void stuckStateAvoidingGoalIsLivenessViolationWithEmptyCycle() {
        Model<Integer> line = RandomGraphs.of(3, new int[][] {{0, 1}, {1, 2}}, Set.of(0));
        Verdict.LivenessViolated<Integer> lv = assertInstanceOf(
                Verdict.LivenessViolated.class, ModelChecker.checkEventually(line, "reach-9", s -> false, 100));
        assertTrue(lv.cycle().isEmpty());
        assertEquals(3, lv.prefix().size()); // 0,1,2 where 2 is stuck
    }

    @Test
    void goalOnEveryPathHolds() {
        Model<Integer> line = RandomGraphs.of(3, new int[][] {{0, 1}, {1, 2}}, Set.of(0));
        assertInstanceOf(Verdict.Holds.class, ModelChecker.checkEventually(line, "reach-2", s -> s == 2, 100));
    }

    @Test
    void goalInitialStateAlwaysHolds() {
        Model<Integer> loop = RandomGraphs.of(2, new int[][] {{0, 1}, {1, 0}}, Set.of(0));
        assertInstanceOf(Verdict.Holds.class, ModelChecker.checkEventually(loop, "g", s -> s == 0, 100));
    }

    @Test
    void selfLoopAvoidingGoalIsViolation() {
        Model<Integer> g = RandomGraphs.of(3, new int[][] {{0, 1}, {1, 1}, {0, 2}}, Set.of(0));
        Verdict.LivenessViolated<Integer> lv = assertInstanceOf(
                Verdict.LivenessViolated.class, ModelChecker.checkEventually(g, "g", s -> s == 2, 100));
        assertEquals(1, lv.cycle().size());
        assertEquals(1, lv.cycle().get(0).state());
    }

    @Test
    void differentialSafetyAgainstBfsDistances() {
        Random rnd = new Random(42);
        int violations = 0;
        int holds = 0;
        for (int round = 0; round < 400; round++) {
            int n = 2 + rnd.nextInt(10);
            int[][] edges = RandomGraphs.randomEdges(rnd, n, rnd.nextInt(2 * n + 1));
            Set<Integer> init = new HashSet<>(List.of(rnd.nextInt(n)));
            if (rnd.nextBoolean()) init.add(rnd.nextInt(n));
            Model<Integer> m = RandomGraphs.of(n, edges, init);
            Set<Integer> bad = new HashSet<>();
            for (int i = 0; i < n; i++) if (rnd.nextInt(5) == 0) bad.add(i);
            Set<Integer> terminals = new HashSet<>();
            for (int i = 0; i < n; i++) if (rnd.nextBoolean()) terminals.add(i);

            int[] dist = RandomGraphs.distances(n, edges, init);
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (dist[i] < 0) continue;
                boolean stuck = RandomGraphs.outDegree(edges, i) == 0 && !terminals.contains(i);
                if (bad.contains(i) || stuck) best = Math.min(best, dist[i]);
            }
            Verdict<Integer> v = ModelChecker.checkSafety(
                    m, Map.of("ok", s -> !bad.contains(s)), terminals::contains, 10_000);
            if (best == Integer.MAX_VALUE) {
                holds++;
                Verdict.Holds<Integer> h = assertInstanceOf(Verdict.Holds.class, v, "round " + round);
                int reach = 0;
                for (int d : dist) if (d >= 0) reach++;
                assertEquals(reach, h.states());
            } else {
                violations++;
                List<Step<Integer>> trace = v instanceof Verdict.InvariantViolated<Integer> iv
                        ? iv.trace()
                        : assertInstanceOf(Verdict.Deadlock.class, v, "round " + round).trace();
                assertEquals(best + 1, trace.size(), "round " + round);
                assertValidTrace(m, trace);
            }
        }
        assertTrue(violations > 50 && holds > 50, "mix " + violations + "/" + holds);
    }

    @Test
    void differentialLivenessAgainstGreatestFixpoint() {
        Random rnd = new Random(7);
        int violated = 0;
        int held = 0;
        for (int round = 0; round < 500; round++) {
            int n = 2 + rnd.nextInt(9);
            int[][] edges = RandomGraphs.randomEdges(rnd, n, rnd.nextInt(2 * n));
            Set<Integer> init = new HashSet<>(List.of(rnd.nextInt(n)));
            Model<Integer> m = RandomGraphs.of(n, edges, init);
            Set<Integer> goal = new HashSet<>();
            for (int i = 0; i < n; i++) if (rnd.nextInt(4) == 0) goal.add(i);

            boolean expected = RandomGraphs.egViolation(n, edges, init, goal);
            Verdict<Integer> v = ModelChecker.checkEventually(m, "g", goal::contains, 10_000);
            if (expected) {
                violated++;
                Verdict.LivenessViolated<Integer> lv = assertInstanceOf(Verdict.LivenessViolated.class, v, "round " + round);
                List<Step<Integer>> full = new ArrayList<>(lv.prefix());
                full.addAll(lv.cycle());
                assertValidTrace(m, full);
                for (Step<Integer> s : full) assertFalse(goal.contains(s.state()), "round " + round);
                if (lv.cycle().isEmpty()) {
                    assertEquals(0, RandomGraphs.outDegree(edges, lv.prefix().get(lv.prefix().size() - 1).state()));
                } else {
                    assertEquals(lv.prefix().get(lv.prefix().size() - 1).state(),
                            lv.cycle().get(lv.cycle().size() - 1).state());
                }
            } else {
                held++;
                assertInstanceOf(Verdict.Holds.class, v, "round " + round);
            }
        }
        assertTrue(violated > 80 && held > 80, "mix " + violated + "/" + held);
    }
}
