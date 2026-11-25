package recon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RolloutInvariantTest {

    static void assertCapacity(Cluster c, String... nodes) {
        for (String n : nodes) {
            assertTrue(c.freeCpu(n) >= 0, "node " + n + " over-committed: " + c.pods());
        }
    }

    @ParameterizedTest
    @CsvSource({
        // replicas, surge, unavailable
        "4, 1, 0",
        "4, 0, 1",
        "5, 2, 2",
        "6, 3, 1",
        "1, 1, 0",
        "3, 0, 3",
    })
    void rolloutNeverBreaksSurgeOrAvailabilityBounds(int replicas, int surge, int unavail) {
        Cluster c = new Cluster();
        IntStream.range(0, 4).forEach(i -> c.addNode("n" + i, 20));
        c.apply(new DeploymentSpec("web", replicas, "v1", 2, surge, unavail));
        for (int i = 0; i < 4; i++) {
            c.tick();
        }
        assertTrue(c.settled());

        c.apply(new DeploymentSpec("web", replicas, "v2", 2, surge, unavail));
        int minAvail = Math.max(0, replicas - unavail);
        int ticks = 0;
        while (!c.settled()) {
            c.tick();
            ticks++;
            assertTrue(ticks < 50, "rollout did not converge: " + c.pods());
            assertTrue(c.pods("web").size() <= replicas + surge, "surge exceeded: " + c.pods());
            assertTrue(c.running("web") >= minAvail, "availability lost at tick " + ticks + ": " + c.pods());
            assertCapacity(c, "n0", "n1", "n2", "n3");
        }
        assertTrue(c.pods("web").stream().allMatch(p -> p.version().equals("v2")));
    }

    @Test
    void rolloutWithZeroUnavailableAndOneSurgeTakesOnePodAtATime() {
        // replicas 3, surge 1, unavailable 0: each wave replaces exactly one pod
        // wave k: create 1 (tick), it runs (tick), old one deleted the tick it is running.
        Cluster c = new Cluster();
        c.addNode("n", 100);
        c.apply(new DeploymentSpec("web", 3, "v1", 1, 1, 0));
        c.tick();
        c.tick();
        c.apply(new DeploymentSpec("web", 3, "v2", 1, 1, 0));
        int seenMax = 0;
        int seenMin = 99;
        for (int i = 0; i < 12 && !c.settled(); i++) {
            c.tick();
            seenMax = Math.max(seenMax, c.pods("web").size());
            seenMin = Math.min(seenMin, c.running("web"));
        }
        assertTrue(c.settled());
        assertEquals(4, seenMax);
        assertEquals(3, seenMin);
    }

    @Test
    void rolloutStallsButKeepsMinimumAvailabilityWhenNewVersionNeverFits() {
        Cluster c = new Cluster();
        c.addNode("n1", 8);
        c.apply(new DeploymentSpec("web", 2, "v1", 4, 1, 1)); // exactly fills the node
        c.tick();
        c.tick();
        assertTrue(c.settled());

        c.apply(new DeploymentSpec("web", 2, "v2", 6, 1, 1)); // 6 cpu never fits next to a 4 cpu pod on n1
        for (int i = 0; i < 20; i++) {
            c.tick();
            assertTrue(c.running("web") >= 1, "dropped below minAvail: " + c.pods());
            assertCapacity(c, "n1");
        }
        // one old pod was deleted (maxUnavailable 1); 4 cpu free is still below 6, so v2 stays unbound.
        // total = replicas 2 + surge 1 = 3: the old pod plus two unbound v2 pods.
        assertEquals(3, c.pods("web").size());
        assertEquals(1, c.pods("web").stream().filter(p -> p.version().equals("v1")).count());
        assertEquals(2, c.pods("web").stream().filter(p -> p.version().equals("v2") && p.node() == null).count());
        assertFalse(c.settled());
        c.addNode("n2", 8);
        c.addNode("n3", 8);
        for (int i = 0; i < 10; i++) {
            c.tick();
        }
        assertTrue(c.settled());
    }

    @Test
    void unhealthyOldPodsAreRemovedWithoutRespectingAvailability() {
        // Fresh deployment never ran; apply a new version while its pods are still pending/unbound.
        Cluster c = new Cluster();
        c.apply(new DeploymentSpec("web", 2, "v1", 1, 1, 1)); // no nodes: pods stay unbound
        c.tick();
        assertEquals(2, c.pods("web").size());
        c.apply(new DeploymentSpec("web", 2, "v2", 1, 1, 1));
        c.tick();
        assertTrue(c.pods("web").stream().noneMatch(p -> p.version().equals("v1")));
    }
}
