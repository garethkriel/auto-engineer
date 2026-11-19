package ae.spatial;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ClusterTest {
    @Test
    void twoClustersAndNoise() {
        SpatialIndex ix = new SpatialIndex();
        // cluster one: a chain at spacing 1 (eps 1.5), ids deliberately out of spatial order
        ix.insert("c", 2, 0);
        ix.insert("a", 0, 0);
        ix.insert("b", 1, 0);
        // cluster two far away
        ix.insert("x", 50, 50);
        ix.insert("y", 50, 51);
        ix.insert("z", 51, 50);
        ix.insert("lone", -30, 9);
        Clusters r = ix.cluster(1.5, 3);
        // a has neighbours {a,b}: 2 < 3, not core. b has {a,b,c}: core. c has {b,c}: not core.
        assertEquals(List.of(List.of("a", "b", "c"), List.of("x", "y", "z")), r.clusters());
        assertEquals(List.of("lone"), r.noise());
    }

    @Test
    void borderPointJoinsFirstClusterThatReachesIt() {
        SpatialIndex ix = new SpatialIndex();
        // eps 1.6, minPts 4. Blob A is the unit square at the origin, blob B the unit square at x=4.
        ix.insert("a1", 0, 0);
        ix.insert("a2", 0, 1);
        ix.insert("a3", 1, 0);
        ix.insert("a4", 1, 1);
        ix.insert("b1", 4, 0);
        ix.insert("b2", 4, 1);
        ix.insert("b3", 5, 0);
        ix.insert("b4", 5, 1);
        // m is 1.5 from a3 and from b1 only: neighbours {m, a3, b1} = 3 < 4, so m is a border point.
        ix.insert("m", 2.5, 0);
        Clusters r = ix.cluster(1.6, 4);
        // a1 is processed first; its expansion reaches m through a3, so B cannot claim it.
        assertEquals(List.of(List.of("a1", "a2", "a3", "a4", "m"), List.of("b1", "b2", "b3", "b4")), r.clusters());
        assertEquals(List.of(), r.noise());
    }

    @Test
    void minPtsOneMakesEveryPointCore() {
        SpatialIndex ix = new SpatialIndex();
        ix.insert("p", 0, 0);
        ix.insert("q", 100, 0);
        Clusters r = ix.cluster(0, 1);
        assertEquals(List.of(List.of("p"), List.of("q")), r.clusters());
        assertTrue(r.noise().isEmpty());
    }

    @Test
    void emptyIndexAndBadArguments() {
        SpatialIndex ix = new SpatialIndex();
        Clusters r = ix.cluster(1, 2);
        assertTrue(r.clusters().isEmpty());
        assertTrue(r.noise().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ix.cluster(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> ix.cluster(1, 0));
    }

    /** Brute-force DBSCAN with the specified visiting order. */
    private static Clusters oracle(List<Entry> sortedById, double eps, int minPts) {
        int n = sortedById.size();
        int[] label = new int[n];
        java.util.Arrays.fill(label, -1);
        List<List<Integer>> neigh = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Integer> nb = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                Point a = sortedById.get(i).point();
                Point b = sortedById.get(j).point();
                double dx = a.x() - b.x();
                double dy = a.y() - b.y();
                if (dx * dx + dy * dy <= eps * eps) {
                    nb.add(j);
                }
            }
            final int self = i;
            nb.sort((p, q) -> {
                Point a = sortedById.get(self).point();
                double dp = Math.pow(a.x() - sortedById.get(p).point().x(), 2) + Math.pow(a.y() - sortedById.get(p).point().y(), 2);
                double dq = Math.pow(a.x() - sortedById.get(q).point().x(), 2) + Math.pow(a.y() - sortedById.get(q).point().y(), 2);
                return dp != dq ? Double.compare(dp, dq) : Integer.compare(p, q);
            });
            neigh.add(nb);
        }
        int clusters = 0;
        for (int i = 0; i < n; i++) {
            if (label[i] != -1 || neigh.get(i).size() < minPts) {
                continue;
            }
            label[i] = clusters;
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(neigh.get(i));
            while (!queue.isEmpty()) {
                int j = queue.poll();
                if (label[j] != -1) {
                    continue;
                }
                label[j] = clusters;
                if (neigh.get(j).size() >= minPts) {
                    queue.addAll(neigh.get(j));
                }
            }
            clusters++;
        }
        List<List<String>> out = new ArrayList<>();
        for (int c = 0; c < clusters; c++) {
            out.add(new ArrayList<>());
        }
        List<String> noise = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (label[i] == -1) {
                noise.add(sortedById.get(i).id());
            } else {
                out.get(label[i]).add(sortedById.get(i).id());
            }
        }
        return new Clusters(out, noise);
    }

    @Test
    void matchesBruteForceOnRandomBlobs() {
        for (long seed = 1; seed <= 25; seed++) {
            Random rnd = new Random(seed);
            SpatialIndex ix = new SpatialIndex();
            int n = 40 + rnd.nextInt(60);
            for (int i = 0; i < n; i++) {
                double x = rnd.nextInt(30) * 0.5;
                double y = rnd.nextInt(30) * 0.5;
                ix.insert(String.format("%03d", i), x, y);
            }
            double eps = 0.5 + rnd.nextInt(4) * 0.5;
            int minPts = 2 + rnd.nextInt(4);
            List<Entry> all = ix.range(new Rect(-1e9, -1e9, 1e9, 1e9));
            Clusters expected = oracle(all, eps, minPts);
            assertEquals(expected, ix.cluster(eps, minPts), "seed " + seed + " eps " + eps + " minPts " + minPts);
        }
    }
}
