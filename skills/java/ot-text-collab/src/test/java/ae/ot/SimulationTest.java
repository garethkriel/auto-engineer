package ae.ot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SimulationTest {

    @Test
    void manySeedsConvergeToServerDocument() {
        int conflicts = 0;
        int totalOps = 0;
        for (long seed = 1; seed <= 400; seed++) {
            int clients = 2 + (int) (seed % 4);       // 2..5
            OtSimulation sim = new OtSimulation(seed, clients, "the quick brown fox");
            OtSimulation.Report r = sim.run(30);
            assertEquals(30, r.edits());
            assertTrue(r.converged(), "seed " + seed);
            assertEquals(sim.serverDocument(), sim.clientDocument(0));
            for (int c = 1; c < clients; c++) assertEquals(sim.serverDocument(), sim.clientDocument(c), "seed " + seed + " client " + c);
            conflicts += r.staleSubmissions();
            totalOps += r.committed();
        }
        // the schedule must really interleave concurrent edits, otherwise convergence proves nothing
        assertTrue(conflicts > 1000, "stale submissions " + conflicts);
        assertTrue(totalOps > 400, "committed ops " + totalOps);
    }

    @Test
    void runIsDeterministicPerSeed() {
        OtSimulation a = new OtSimulation(42, 3, "seed");
        OtSimulation b = new OtSimulation(42, 3, "seed");
        a.run(25);
        b.run(25);
        assertEquals(a.serverDocument(), b.serverDocument());
        assertEquals(a.serverHistory(), b.serverHistory());
        OtSimulation c = new OtSimulation(43, 3, "seed");
        c.run(25);
        assertNotEquals(a.serverHistory(), c.serverHistory());
    }

    @Test
    void serverHistoryReplaysToDocument() {
        OtSimulation sim = new OtSimulation(7, 4, "abc");
        sim.run(40);
        String d = "abc";
        for (TextOp o : sim.serverHistory()) d = o.apply(d);
        assertEquals(sim.serverDocument(), d);
    }

    @Test
    void singleClientNeverConflicts() {
        OtSimulation sim = new OtSimulation(3, 1, "x");
        OtSimulation.Report r = sim.run(20);
        assertTrue(r.converged());
        assertEquals(0, r.staleSubmissions());
    }

    @Test
    void invariantCheckerDetectsCorruptedClient() {
        OtSimulation sim = new OtSimulation(5, 2, "abc");
        sim.run(10);
        assertTrue(sim.checkInvariants().isEmpty());
        sim.corruptClientDocumentForTest(0, "ZZZ");
        assertFalse(sim.checkInvariants().isEmpty());
    }

    @Test
    void invalidConstructionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OtSimulation(1, 0, "a"));
        assertThrows(NullPointerException.class, () -> new OtSimulation(1, 2, null));
    }
}
