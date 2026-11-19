package ae.swim;

import static ae.swim.Status.ALIVE;
import static ae.swim.Status.DEAD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SimulationTest {
    // period 8 ticks, direct timeout 3, 2 indirect probes, suspicion 64 (8 periods: must exceed gossip spread time), 4 updates/message, retransmit 3*ceil(log2(n+1))
    static final Config CFG = new Config(8, 3, 2, 64, 4, 3);

    static List<String> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> "n" + i).toList();
    }

    @Test
    void losslessNetworkNeverSuspectsAnyone() {
        for (long seed = 1; seed <= 10; seed++) {
            Simulation sim = new Simulation(ids(8), CFG, seed, 0.0);
            for (int t = 0; t < 400; t++) {
                sim.step();
                for (String o : ids(8)) {
                    for (var e : sim.node(o).view().entrySet()) {
                        assertEquals(ALIVE, e.getValue(), "seed " + seed + " t=" + t + " " + o + " sees " + e.getKey());
                    }
                }
            }
        }
    }

    @Test
    void everyCrashIsEventuallyDeadEverywhereAndNotBeforeTheSuspicionTimeout() {
        for (long seed = 1; seed <= 10; seed++) {
            Simulation sim = new Simulation(ids(10), CFG, seed, 0.0);
            sim.run(50);
            sim.crash("n3");
            sim.crash("n7");
            long crashedAt = sim.now();
            long done = sim.runUntil(() -> allLiveSee(sim, List.of("n3", "n7"), DEAD), 2000);
            assertTrue(done >= 0, "seed " + seed + " never converged: " + sim.node("n0").view());
            // A probe period (8) must pass before suspicion starts, then the 64-tick timeout.
            assertTrue(done - crashedAt >= 64 + 1, "seed " + seed + " too fast: " + (done - crashedAt));
            // Detection bound: <= 10 probe periods (80) + 64 suspicion + gossip slack (<= 8 periods = 64).
            assertTrue(done - crashedAt <= 80 + 64 + 64, "seed " + seed + " too slow: " + (done - crashedAt));
            for (String live : ids(10)) {
                if (live.equals("n3") || live.equals("n7")) continue;
                assertEquals(ALIVE, sim.node(live).status(live.equals("n0") ? "n1" : "n0"));
            }
        }
    }

    @Test
    void oneBrokenLinkIsMaskedByIndirectProbes() {
        for (long seed = 1; seed <= 10; seed++) {
            Simulation sim = new Simulation(ids(6), CFG, seed, 0.0);
            sim.blockLink("n0", "n1");
            for (int t = 0; t < 600; t++) {
                sim.step();
                for (String o : ids(6)) {
                    for (var e : sim.node(o).view().entrySet()) {
                        assertEquals(ALIVE, e.getValue(), "seed " + seed + " t=" + t + ": " + o + " -> " + e.getKey());
                    }
                }
            }
        }
    }

    @Test
    void permanentIsolationIsASymmetricSplitBrainWithNoRejoin() {
        Simulation sim = new Simulation(ids(6), CFG, 5, 0.0);
        sim.run(20);
        for (String o : ids(6)) if (!o.equals("n2")) sim.blockLink("n2", o);
        long done = sim.runUntil(() -> allLiveSee(sim, List.of("n2"), DEAD, "n2"), 1500);
        assertTrue(done >= 0, "others never declared n2 dead");
        // n2 hears nothing, so it buries everyone too and then has nobody left to probe.
        long mutual = sim.runUntil(() -> sim.node("n2").view().values().stream().allMatch(s -> s == DEAD), 1500);
        assertTrue(mutual >= 0, "isolated node never gave up on the others: " + sim.node("n2").view());
        assertTrue(!sim.node("n2").isDeclaredDead(), "nobody could tell n2 about its death");
        for (String o : ids(6)) if (!o.equals("n2")) sim.healLink("n2", o);
        sim.run(300);
        // DEAD is absorbing: healing the network does not resurrect anyone (documented limitation: no rejoin).
        assertEquals(DEAD, sim.node("n0").status("n2"));
        assertEquals(DEAD, sim.node("n2").status("n0"));
    }

    @Test
    void shortPartitionIsRefutedAndEveryoneEndsAliveWithBumpedIncarnation() {
        boolean sawRefutation = false;
        for (long seed = 1; seed <= 10; seed++) {
            Simulation sim = new Simulation(ids(6), CFG, seed, 0.0);
            sim.run(20);
            for (String o : ids(6)) if (!o.equals("n4")) sim.blockLink("n4", o);
            sim.run(20); // 20 < 64 suspicion ticks: suspected, never dead
            for (String o : ids(6)) if (!o.equals("n4")) sim.healLink("n4", o);
            sim.run(400);
            for (String o : ids(6)) {
                for (var e : sim.node(o).view().entrySet()) {
                    assertEquals(ALIVE, e.getValue(), "seed " + seed + ": " + o + " sees " + e.getKey());
                }
            }
            int inc = sim.node("n4").selfIncarnation();
            for (String o : ids(6)) {
                if (!o.equals("n4")) assertEquals(inc, sim.node(o).incarnation("n4"), "seed " + seed + " observer " + o);
            }
            sawRefutation |= inc > 0;
        }
        assertTrue(sawRefutation, "at least one seed must exercise a real refutation");
    }

    @Test
    void lossyNetworkSuspectsFalselyButRefutesBeforeAnyoneIsDeclaredDead() {
        int suspicions = 0;
        for (long seed = 1; seed <= 12; seed++) {
            Simulation sim = new Simulation(ids(8), CFG, seed, 0.15);
            for (int t = 0; t < 800; t++) {
                sim.step();
                for (String o : ids(8)) {
                    for (var e : sim.node(o).view().entrySet()) {
                        assertNotEquals(DEAD, e.getValue(), "seed " + seed + " t=" + t + ": " + o + " buried " + e.getKey());
                    }
                }
            }
            for (String o : ids(8)) suspicions += sim.node(o).selfIncarnation();
        }
        assertTrue(suspicions > 0, "15% loss should cause at least one refuted false suspicion across the seeds");
    }

    @Test
    void sameSeedSameTraceDifferentSeedDifferentTrace() {
        List<String> a = traceOf(42), b = traceOf(42), c = traceOf(43);
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertTrue(a.size() > 100, "trace should be non-trivial: " + a.size());
    }

    private static List<String> traceOf(long seed) {
        Simulation sim = new Simulation(ids(6), CFG, seed, 0.1);
        sim.run(60);
        sim.crash("n1");
        sim.run(200);
        return new ArrayList<>(sim.trace());
    }

    @Test
    void invariantCheckerCatchesForcedResurrection() {
        Simulation sim = new Simulation(ids(4), CFG, 3, 0.0);
        sim.node("n0").debugForce("n1", DEAD, 0);
        sim.step(); // records DEAD for (n0, n1)
        sim.node("n0").debugForce("n1", ALIVE, 5);
        IllegalStateException ex = assertThrows(IllegalStateException.class, sim::step);
        assertTrue(ex.getMessage().contains("DEAD"), ex.getMessage());
    }

    @Test
    void invariantCheckerCatchesIncarnationAheadOfSubject() {
        Simulation sim = new Simulation(ids(4), CFG, 3, 0.0);
        sim.node("n0").debugForce("n2", ALIVE, 9); // n2's own incarnation is 0
        IllegalStateException ex = assertThrows(IllegalStateException.class, sim::step);
        assertTrue(ex.getMessage().contains("incarnation"), ex.getMessage());
    }

    @Test
    void invariantCheckerCatchesDecreasingIncarnation() {
        Simulation sim = new Simulation(ids(4), CFG, 3, 0.0);
        // make n2 legitimately have incarnation 3 by refuting suspicions fed to it directly
        for (int i = 0; i < 3; i++) {
            sim.node("n2").receive(new Ping("n1", "n2", 900 + i, List.of(new Update("n2", Status.SUSPECT, i))), 0);
        }
        assertEquals(3, sim.node("n2").selfIncarnation());
        sim.node("n0").debugForce("n2", ALIVE, 3);
        sim.checkInvariants(); // records 3
        sim.node("n0").debugForce("n2", ALIVE, 2);
        IllegalStateException ex = assertThrows(IllegalStateException.class, sim::checkInvariants);
        assertTrue(ex.getMessage().contains("decreased"), ex.getMessage());
    }

    @Test
    void crashedNodesSendAndReceiveNothing() {
        Simulation sim = new Simulation(ids(3), CFG, 1, 0.0);
        sim.crash("n1");
        sim.run(50);
        for (String line : sim.trace()) {
            assertTrue(!line.contains("n1->") || line.contains("DROP"), line);
        }
        assertThrows(IllegalArgumentException.class, () -> sim.crash("nope"));
    }

    private static boolean allLiveSee(Simulation sim, List<String> subjects, Status s, String... skipObservers) {
        List<String> skip = List.of(skipObservers);
        for (String o : ids(sim.size())) {
            if (sim.isCrashed(o) || skip.contains(o)) continue;
            for (String subj : subjects) {
                if (o.equals(subj)) continue;
                if (sim.node(o).status(subj) != s) return false;
            }
        }
        return true;
    }
}
