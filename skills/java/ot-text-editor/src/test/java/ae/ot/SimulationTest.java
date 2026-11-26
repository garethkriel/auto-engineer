package ae.ot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SimulationTest {

    @Test
    void twoClientsSameSpotConverge() {
        Simulation sim = new Simulation(7, "hello", 2);
        sim.edit(0, TextOpTest.randomOp("hello", new java.util.Random(1)));
        sim.edit(1, new TextOp.Builder().retain(5).insert("!").build());
        sim.runToQuiescence();
        assertTrue(sim.converged(), sim.report());
    }

    @Test
    void seededRunsConvergeAndServerLogReplays() {
        int totalOps = 0;
        for (long seed = 1; seed <= 60; seed++) {
            int clients = 2 + (int) (seed % 4); // 2..5
            Simulation sim = new Simulation(seed, "start", clients);
            sim.runRandom(80);
            assertTrue(sim.converged(), "seed " + seed + "\n" + sim.report());
            assertEquals(sim.server().replayLog(), sim.server().document(), "seed " + seed);
            assertEquals(sim.server().revision(), sim.client(0).revision(), "seed " + seed);
            totalOps += sim.server().revision();
        }
        assertTrue(totalOps > 1500, "workload too light: " + totalOps);
    }

    @Test
    void emptyInitialDocumentConverges() {
        for (long seed = 100; seed < 130; seed++) {
            Simulation sim = new Simulation(seed, "", 3);
            sim.runRandom(60);
            assertTrue(sim.converged(), "seed " + seed + "\n" + sim.report());
        }
    }

    @Test
    void divergenceDetectorCanFail() {
        // Control: break convergence on purpose by editing a client's local copy only.
        Simulation sim = new Simulation(3, "abc", 2);
        sim.runToQuiescence();
        assertTrue(sim.converged());
        sim.corruptClientForTest(1, "abX");
        assertFalse(sim.converged());
    }

    @Test
    void scheduleActuallyInterleaves() {
        // Edits from several clients must be in flight at once for a nontrivial share of seeds.
        int concurrent = 0;
        for (long seed = 1; seed <= 40; seed++) {
            Simulation sim = new Simulation(seed, "abcdef", 3);
            sim.runRandom(40);
            concurrent += sim.maxInFlight() >= 2 ? 1 : 0;
            sim.runToQuiescence();
        }
        assertTrue(concurrent >= 30, "only " + concurrent + " of 40 seeds had >=2 ops in flight");
    }
}
