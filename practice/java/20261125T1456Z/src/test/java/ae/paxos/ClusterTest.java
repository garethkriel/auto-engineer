package ae.paxos;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClusterTest {
    private static boolean runUntilApplied(Cluster c, Set<String> want, int maxSteps) {
        for (int i = 0; i < maxSteps; i++) {
            c.step();
            c.checkSafety();
            boolean done = true;
            for (int id = 0; id < c.size(); id++) {
                if (c.isCrashed(id)) continue;
                if (!new HashSet<>(c.node(id).appliedLog()).containsAll(want)) { done = false; break; }
            }
            if (done) return true;
        }
        return false;
    }

    @Test
    void singleCommandDecidedEverywhereOnPerfectNetwork() {
        Cluster c = new Cluster(3, 1, 0, 0, 1);
        c.submit(0, "a");
        assertTrue(runUntilApplied(c, Set.of("a"), 200));
        for (int i = 0; i < 3; i++) assertEquals(List.of("a"), c.node(i).appliedLog());
    }

    @Test
    void duelingProposersAllCommandsDecidedWithIdenticalLogs() {
        Cluster c = new Cluster(5, 7, 0, 0, 3);
        Set<String> want = new HashSet<>();
        for (int i = 0; i < 5; i++) { c.submit(i, "c" + i); want.add("c" + i); }
        assertTrue(runUntilApplied(c, want, 3000));
        for (int i = 0; i < 40; i++) { c.step(); c.checkSafety(); }
        List<String> ref = c.node(0).appliedLog();
        assertEquals(want, new HashSet<>(ref));
        assertEquals(5, ref.size());
        for (int i = 1; i < 5; i++) assertEquals(ref, c.node(i).appliedLog());
    }

    @Test
    void minorityPartitionCannotDecide() {
        Cluster c = new Cluster(5, 3, 0, 0, 2);
        c.isolate(0); // node 0 alone
        c.submit(0, "lonely");
        for (int i = 0; i < 400; i++) { c.step(); c.checkSafety(); }
        for (int i = 0; i < 5; i++) assertTrue(c.node(i).decided().isEmpty(), "node " + i);
        c.heal();
        assertTrue(runUntilApplied(c, Set.of("lonely"), 1500));
    }

    @Test
    void evenClusterNeedsThreeOfFour() {
        Cluster c = new Cluster(4, 3, 0, 0, 2);
        // split 2|2: neither side has a quorum of 3
        c.block(0, 2); c.block(0, 3); c.block(1, 2); c.block(1, 3);
        c.submit(0, "x");
        c.submit(2, "y");
        for (int i = 0; i < 400; i++) { c.step(); c.checkSafety(); }
        for (int i = 0; i < 4; i++) assertTrue(c.node(i).decided().isEmpty(), "node " + i);
        c.heal();
        assertTrue(runUntilApplied(c, Set.of("x", "y"), 3000));
    }

    @Test
    void majorityPartitionStillDecidesAndMinorityCatchesUpAfterHeal() {
        Cluster c = new Cluster(5, 11, 0, 0, 2);
        c.isolate(4);
        c.submit(0, "m1");
        for (int i = 0; i < 300; i++) { c.step(); c.checkSafety(); }
        assertTrue(c.node(0).appliedLog().contains("m1"));
        assertTrue(c.node(4).decided().isEmpty());
        c.heal();
        assertTrue(runUntilApplied(c, Set.of("m1"), 500));
    }

    @Test
    void leaderCrashAfterAcceptsDoesNotLoseOrChangeChosenValue() {
        for (long seed = 1; seed <= 25; seed++) {
            Cluster c = new Cluster(3, seed, 0.1, 0.1, 4);
            c.submit(0, "first");
            for (int i = 0; i < 12; i++) { c.step(); c.checkSafety(); }
            c.crash(0);
            c.submit(1, "second");
            for (int i = 0; i < 60; i++) { c.step(); c.checkSafety(); }
            c.restart(0);
            c.setLossless();
            c.submit(0, "first"); // the client retries after the crash
            assertTrue(runUntilApplied(c, Set.of("first", "second"), 3000), "seed " + seed);
            List<String> ref = c.node(1).appliedLog();
            for (int i = 0; i < 3; i++) assertEquals(ref, c.node(i).appliedLog(), "seed " + seed);
            assertEquals(2, ref.size(), "dedup, seed " + seed);
        }
    }

    @Test
    void chaosManySeedsKeepSafetyAndConvergeAfterHeal() {
        int[] sizes = {3, 5};
        int totalDecided = 0;
        for (int n : sizes) {
            for (long seed = 1; seed <= 40; seed++) {
                Cluster c = new Cluster(n, seed * 31 + n, 0.2, 0.15, 5);
                Random rnd = new Random(seed);
                Set<String> want = new HashSet<>();
                int k = 0;
                for (int step = 0; step < 400; step++) {
                    if (step % 20 == 0 && k < 8) {
                        String cmd = "c" + (k++);
                        want.add(cmd);
                        c.submit(rnd.nextInt(n), cmd);
                    }
                    if (step % 50 == 25) {
                        int victim = rnd.nextInt(n);
                        switch (rnd.nextInt(3)) {
                            case 0 -> c.isolate(victim);
                            case 1 -> { if (!c.isCrashed(victim)) c.crash(victim); }
                            default -> { c.heal(); for (int i = 0; i < n; i++) if (c.isCrashed(i)) c.restart(i); }
                        }
                    }
                    c.step();
                    c.checkSafety();
                }
                c.heal();
                for (int i = 0; i < n; i++) if (c.isCrashed(i)) c.restart(i);
                c.setLossless();
                // commands owned by nodes that crashed were lost: client retries to a live node
                for (String cmd : want) c.submit((int) (seed % n), cmd);
                assertTrue(runUntilApplied(c, want, 6000), "n=" + n + " seed=" + seed);
                List<String> ref = c.node(0).appliedLog();
                for (int i = 1; i < n; i++) assertEquals(ref, c.node(i).appliedLog(), "n=" + n + " seed=" + seed);
                assertEquals(want.size(), new HashSet<>(ref).size());
                assertEquals(want.size(), ref.size(), "applied once each");
                totalDecided += ref.size();
            }
        }
        assertTrue(totalDecided >= 80 * 8, "all runs completed: " + totalDecided);
    }

    @Test
    void checkerDetectsCorruptedAgreement() {
        Cluster c = new Cluster(3, 1, 0, 0, 1);
        c.submit(0, "a");
        runUntilApplied(c, Set.of("a"), 200);
        c.corruptDecided(2, 0, "evil");
        IllegalStateException e = assertThrows(IllegalStateException.class, c::checkSafety);
        assertTrue(e.getMessage().startsWith("agreement"), e.getMessage());
    }

    @Test
    void checkerDetectsUnsubmittedValueAndPromiseRegression() {
        Cluster c = new Cluster(3, 1, 0, 0, 1);
        c.corruptDecided(1, 4, "never-submitted");
        IllegalStateException e = assertThrows(IllegalStateException.class, c::checkSafety);
        assertTrue(e.getMessage().startsWith("validity"), e.getMessage());

        Cluster d = new Cluster(3, 1, 0, 0, 1);
        d.node(0).receive(new Msg.Prepare(1, 0, new Ballot(5, 1)), 0);
        d.checkSafety();
        d.corruptPromised(0, new Ballot(2, 1));
        IllegalStateException e2 = assertThrows(IllegalStateException.class, d::checkSafety);
        assertTrue(e2.getMessage().startsWith("promise regressed"), e2.getMessage());
    }

    @Test
    void sameSeedIsDeterministic() {
        List<List<String>> runs = new ArrayList<>();
        for (int r = 0; r < 2; r++) {
            Cluster c = new Cluster(5, 99, 0.2, 0.1, 4);
            for (int i = 0; i < 5; i++) c.submit(i, "v" + i);
            for (int i = 0; i < 300; i++) c.step();
            runs.add(c.node(0).appliedLog());
        }
        assertEquals(runs.get(0), runs.get(1));
    }
}
