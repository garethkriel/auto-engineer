package recon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterTest {

    private static DeploymentSpec web(int replicas, String version) {
        return new DeploymentSpec("web", replicas, version, 4, 1, 1);
    }

    private static String describe(Cluster c) {
        StringBuilder sb = new StringBuilder();
        for (Pod p : c.pods()) {
            sb.append(p.id()).append(':').append(p.version()).append(':').append(p.phase())
                    .append('@').append(p.node()).append(' ');
        }
        return sb.toString().trim();
    }

    @Test
    void specValidationRejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSpec(" ", 1, "v", 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSpec("a", 1, "", 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSpec("a", -1, "v", 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSpec("a", 1, "v", 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSpec("a", 1, "v", 1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSpec("a", 1, "v", 1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSpec("a", 1, "v", 1, 0, 0));
        assertDoesNotThrow(() -> new DeploymentSpec("a", 0, "v", 1, 0, 1));
    }

    @Test
    void nodeApiValidation() {
        Cluster c = new Cluster();
        c.addNode("n1", 5);
        assertThrows(IllegalArgumentException.class, () -> c.addNode("n1", 5));
        assertThrows(IllegalArgumentException.class, () -> c.addNode("n2", 0));
        assertThrows(IllegalArgumentException.class, () -> c.failNode("zz"));
        assertThrows(IllegalArgumentException.class, () -> c.recoverNode("zz"));
        assertThrows(IllegalArgumentException.class, () -> c.freeCpu("zz"));
    }

    /** Hand-derived trace: two nodes of 10 cpu, pods of 4 cpu, surge 1, unavailable 1. */
    @Test
    void rollingUpdateTraceMatchesHandDerivation() {
        Cluster c = new Cluster();
        c.addNode("n1", 10);
        c.addNode("n2", 10);
        c.apply(web(2, "v1"));

        c.tick(); // creates pods 1,2; tie on free cpu -> n1, then n2 (10 > 6)
        assertEquals("1:v1:PENDING@n1 2:v1:PENDING@n2", describe(c));
        assertFalse(c.settled());

        c.tick(); // kubelet promotes both
        assertEquals("1:v1:RUNNING@n1 2:v1:RUNNING@n2", describe(c));
        assertTrue(c.settled());

        c.apply(web(2, "v2"));
        c.tick(); // surge room 1 -> pod 3; minAvail 1 -> delete pod 1; pod 3 -> n1 (10 vs 6)
        assertEquals("2:v1:RUNNING@n2 3:v2:PENDING@n1", describe(c));

        c.tick(); // pod 3 runs; create pod 4; delete pod 2; pod 4 -> n2 (6 vs 10)
        assertEquals("3:v2:RUNNING@n1 4:v2:PENDING@n2", describe(c));
        assertFalse(c.settled());

        c.tick();
        assertEquals("3:v2:RUNNING@n1 4:v2:RUNNING@n2", describe(c));
        assertTrue(c.settled());
    }

    @Test
    void schedulerLeavesPodUnboundWhenNothingFits() {
        Cluster c = new Cluster();
        c.addNode("small", 3);
        c.apply(web(1, "v1")); // needs 4
        c.tick();
        c.tick();
        assertEquals(List.of(new Pod(1, "web", "v1", 4, Phase.PENDING, null)), c.pods());
        assertEquals(3, c.freeCpu("small"));
        assertFalse(c.settled());
        c.addNode("big", 8);
        c.tick();
        assertEquals("big", c.pods().get(0).node());
        assertEquals(4, c.freeCpu("big"));
    }

    @Test
    void tieBreakPicksSmallerNodeName() {
        Cluster c = new Cluster();
        c.addNode("b", 10);
        c.addNode("a", 10);
        c.apply(new DeploymentSpec("one", 1, "v", 1, 1, 0));
        c.tick();
        assertEquals("a", c.pods().get(0).node());
    }

    @Test
    void failedNodeLosesPodsAndControllerReplacesThemElsewhere() {
        Cluster c = new Cluster();
        c.addNode("n1", 10);
        c.addNode("n2", 10);
        c.apply(web(2, "v1"));
        c.tick();
        c.tick();
        c.failNode("n1");
        assertEquals("2:v1:RUNNING@n2", describe(c));
        assertFalse(c.settled());
        c.tick(); // replacement pod 3 created, only n2 is up (free 6)
        assertEquals("2:v1:RUNNING@n2 3:v1:PENDING@n2", describe(c));
        c.tick();
        assertTrue(c.settled());
        c.recoverNode("n1");
        assertEquals(10, c.freeCpu("n1"));
    }

    @Test
    void podsAreNeverScheduledOnADownNode() {
        Cluster c = new Cluster();
        c.addNode("n1", 10);
        c.failNode("n1");
        c.apply(web(1, "v1"));
        c.tick();
        c.tick();
        assertNull(c.pods().get(0).node());
        assertEquals(Phase.PENDING, c.pods().get(0).phase());
    }

    @Test
    void scaleDownDeletesPendingFirstThenHighestId() {
        Cluster c = new Cluster();
        c.addNode("n1", 100);
        c.apply(new DeploymentSpec("web", 3, "v1", 1, 1, 1));
        c.tick();
        c.tick();
        assertEquals(3, c.running("web"));
        c.apply(new DeploymentSpec("web", 1, "v1", 1, 1, 1));
        c.tick(); // same version: surplus new pods removed, highest id first
        assertEquals("1:v1:RUNNING@n1", describe(c));
        assertTrue(c.settled());
    }

    @Test
    void scaleInPrefersNotRunningPods() {
        Cluster c = new Cluster();
        c.addNode("n1", 100);
        c.apply(new DeploymentSpec("web", 2, "v1", 1, 1, 1));
        c.tick();
        c.tick(); // pods 1,2 running
        c.apply(new DeploymentSpec("web", 3, "v1", 1, 1, 1));
        c.tick(); // pod 3 created and bound, pending
        assertEquals("1:v1:RUNNING@n1 2:v1:RUNNING@n1 3:v1:PENDING@n1", describe(c));
        c.apply(new DeploymentSpec("web", 2, "v1", 1, 1, 1));
        c.tick(); // kubelet runs pod 3 first, so highest id (3) goes
        assertEquals("1:v1:RUNNING@n1 2:v1:RUNNING@n1", describe(c));
    }

    @Test
    void deleteRemovesSpecAndPods() {
        Cluster c = new Cluster();
        c.addNode("n1", 10);
        c.apply(web(2, "v1"));
        c.tick();
        c.tick();
        c.delete("web");
        assertTrue(c.pods().isEmpty());
        assertEquals(10, c.freeCpu("n1"));
        c.tick();
        assertTrue(c.pods().isEmpty());
        assertTrue(c.settled());
    }

    @Test
    void zeroReplicasSettlesEmpty() {
        Cluster c = new Cluster();
        c.addNode("n1", 10);
        c.apply(web(2, "v1"));
        c.tick();
        c.tick();
        c.apply(new DeploymentSpec("web", 0, "v1", 4, 1, 1));
        c.tick();
        assertTrue(c.pods().isEmpty());
        assertTrue(c.settled());
    }

    @Test
    void deploymentsReconcileIndependentlyAndInNameOrder() {
        Cluster c = new Cluster();
        c.addNode("n1", 10);
        c.apply(new DeploymentSpec("zeta", 1, "v", 1, 1, 0));
        c.apply(new DeploymentSpec("alpha", 1, "v", 1, 1, 0));
        c.tick();
        assertEquals("alpha", c.pods().get(0).deployment()); // controllers run in name order -> id 1
        assertEquals("zeta", c.pods().get(1).deployment());
        assertEquals(1, c.pods("alpha").size());
    }

    @Test
    void settledRequiresCurrentVersionOnly() {
        Cluster c = new Cluster();
        c.addNode("n1", 10);
        c.apply(new DeploymentSpec("a", 1, "v1", 1, 1, 0));
        c.tick();
        c.tick();
        assertTrue(c.settled());
        c.apply(new DeploymentSpec("a", 1, "v2", 1, 1, 0));
        assertFalse(c.settled()); // right count and running, but wrong version
    }
}
