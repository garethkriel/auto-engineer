package recon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SimulationTest {

    private static final int NODES = 4;
    private static final int NODE_CPU = 16;

    @Test
    void randomisedFaultsKeepCapacityAndEventuallySettle() {
        for (long seed = 1; seed <= 60; seed++) {
            Random rnd = new Random(seed);
            Cluster c = new Cluster();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < NODES; i++) {
                names.add("n" + i);
                c.addNode("n" + i, NODE_CPU);
            }
            int version = 1;
            // total cpu demand at most 3 deployments * 4 replicas * 1 cpu * (1 + surge) << 64
            for (int step = 0; step < 80; step++) {
                int action = rnd.nextInt(10);
                String dep = "d" + rnd.nextInt(3);
                if (action == 0) {
                    c.failNode(names.get(rnd.nextInt(NODES)));
                } else if (action == 1) {
                    c.recoverNode(names.get(rnd.nextInt(NODES)));
                } else if (action == 2) {
                    version++;
                    c.apply(new DeploymentSpec(dep, 1 + rnd.nextInt(4), "v" + version, 1, rnd.nextInt(3), 1 + rnd.nextInt(2)));
                } else if (action == 3) {
                    c.apply(new DeploymentSpec(dep, rnd.nextInt(5), "v" + version, 1, 1, 1));
                } else if (action == 4 && rnd.nextInt(4) == 0) {
                    c.delete(dep);
                }
                c.tick();
                for (String n : names) {
                    assertTrue(c.freeCpu(n) >= 0, "seed " + seed + " step " + step + " overcommit " + c.pods());
                    assertTrue(c.freeCpu(n) <= NODE_CPU, "seed " + seed + " free above capacity");
                }
                for (Pod p : c.pods()) {
                    assertTrue(p.phase() == Phase.PENDING || p.node() != null, "running pod must be bound");
                }
            }
            for (String n : names) {
                c.recoverNode(n);
            }
            int ticks = 0;
            while (!c.settled() && ticks < 60) {
                c.tick();
                ticks++;
            }
            assertTrue(c.settled(), "seed " + seed + " did not settle: " + c.pods());
        }
    }

    @Test
    void identicalSeedsGiveIdenticalHistories() {
        assertEquals(history(7), history(7));
        assertNotEquals(history(7), history(8));
    }

    private static List<Pod> history(long seed) {
        Random rnd = new Random(seed);
        Cluster c = new Cluster();
        c.addNode("a", 8);
        c.addNode("b", 8);
        c.apply(new DeploymentSpec("x", 3, "v1", 1, 1, 1));
        for (int i = 0; i < 30; i++) {
            if (rnd.nextInt(5) == 0) {
                c.apply(new DeploymentSpec("x", 1 + rnd.nextInt(4), "v" + rnd.nextInt(3), 1, 1, 1));
            }
            c.tick();
        }
        return c.pods();
    }
}
