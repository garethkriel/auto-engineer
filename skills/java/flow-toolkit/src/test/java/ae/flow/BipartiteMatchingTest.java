package ae.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BipartiteMatchingTest {

    @Test
    void smallKnownGraph() {
        // l0:{r0,r1} l1:{r0} l2:{r1,r2} l3:{r2}  -> max matching 3 (right side has 3 vertices)
        BipartiteMatching m = new BipartiteMatching(4, 3);
        m.addEdge(0, 0);
        m.addEdge(0, 1);
        m.addEdge(1, 0);
        m.addEdge(2, 1);
        m.addEdge(2, 2);
        m.addEdge(3, 2);
        assertEquals(3, m.solve());
        int matched = 0;
        for (int l = 0; l < 4; l++) {
            int r = m.matchOfLeft(l);
            if (r >= 0) {
                matched++;
                assertEquals(l, m.matchOfRight(r));
            }
        }
        assertEquals(3, matched);
    }

    @Test
    void emptyGraphAndIsolatedVertices() {
        BipartiteMatching m = new BipartiteMatching(3, 2);
        assertEquals(0, m.solve());
        assertEquals(-1, m.matchOfLeft(1));
        assertEquals(-1, m.matchOfRight(1));
        BipartiteMatching.Cover c = m.minVertexCover();
        assertTrue(c.left().isEmpty());
        assertTrue(c.right().isEmpty());
    }

    @Test
    void accessBeforeSolveAndBadIdsAreRejected() {
        BipartiteMatching m = new BipartiteMatching(2, 2);
        assertThrows(IllegalStateException.class, () -> m.matchOfLeft(0));
        assertThrows(IllegalStateException.class, m::minVertexCover);
        assertThrows(IllegalArgumentException.class, () -> m.addEdge(2, 0));
        assertThrows(IllegalArgumentException.class, () -> m.addEdge(0, -1));
        m.addEdge(0, 0);
        m.solve();
        assertThrows(IllegalArgumentException.class, () -> m.matchOfLeft(5));
        assertThrows(IllegalArgumentException.class, () -> new BipartiteMatching(-1, 1));
    }

    @Test
    void solveAgainAfterAddingEdges() {
        BipartiteMatching m = new BipartiteMatching(2, 2);
        m.addEdge(0, 0);
        m.addEdge(1, 0);
        assertEquals(1, m.solve());
        m.addEdge(1, 1);
        assertEquals(2, m.solve());
    }

    /** Oracle: Kuhn's algorithm, recursive (small inputs only). */
    private static int kuhn(List<List<Integer>> adj, int rightCount) {
        int[] matchR = new int[rightCount];
        java.util.Arrays.fill(matchR, -1);
        int size = 0;
        for (int l = 0; l < adj.size(); l++) {
            if (try1(l, adj, matchR, new boolean[rightCount])) {
                size++;
            }
        }
        return size;
    }

    private static boolean try1(int l, List<List<Integer>> adj, int[] matchR, boolean[] seen) {
        for (int r : adj.get(l)) {
            if (seen[r]) {
                continue;
            }
            seen[r] = true;
            if (matchR[r] < 0 || try1(matchR[r], adj, matchR, seen)) {
                matchR[r] = l;
                return true;
            }
        }
        return false;
    }

    @Test
    void randomGraphsMatchKuhnAndKonigCoverIsValid() {
        Random rnd = new Random(3);
        for (int trial = 0; trial < 500; trial++) {
            int nl = 1 + rnd.nextInt(8);
            int nr = 1 + rnd.nextInt(8);
            BipartiteMatching m = new BipartiteMatching(nl, nr);
            List<List<Integer>> adj = new ArrayList<>();
            for (int i = 0; i < nl; i++) {
                adj.add(new ArrayList<>());
            }
            List<int[]> edges = new ArrayList<>();
            int count = rnd.nextInt(nl * nr + 1);
            for (int i = 0; i < count; i++) {
                int l = rnd.nextInt(nl);
                int r = rnd.nextInt(nr);
                m.addEdge(l, r);
                adj.get(l).add(r);
                edges.add(new int[] {l, r});
            }
            int size = m.solve();
            String msg = "trial " + trial;
            assertEquals(kuhn(adj, nr), size, msg);
            // matching validity
            boolean[] usedR = new boolean[nr];
            for (int l = 0; l < nl; l++) {
                int r = m.matchOfLeft(l);
                if (r >= 0) {
                    assertTrue(adj.get(l).contains(r), msg);
                    assertFalse(usedR[r], msg);
                    usedR[r] = true;
                }
            }
            // Konig: cover covers every edge and has size == matching size
            BipartiteMatching.Cover cover = m.minVertexCover();
            assertEquals(size, cover.left().size() + cover.right().size(), msg);
            for (int[] e : edges) {
                assertTrue(cover.left().contains(e[0]) || cover.right().contains(e[1]), msg + " edge " + e[0] + "-" + e[1]);
            }
        }
    }

    @Test
    void longAugmentingPathsDoNotOverflowTheStack() {
        int n = 100_000;
        BipartiteMatching m = new BipartiteMatching(n, n + 1);
        // l_i adjacent to r_{i+1} first, then r_i: greedy picks r_{i+1}, forcing cascades
        for (int i = 0; i < n; i++) {
            m.addEdge(i, i + 1);
            m.addEdge(i, i);
        }
        assertEquals(n, m.solve());
    }

    @Test
    void largeRandomGraphIsFast() {
        int n = 2000;
        Random rnd = new Random(5);
        BipartiteMatching m = new BipartiteMatching(n, n);
        for (int l = 0; l < n; l++) {
            for (int k = 0; k < 5; k++) {
                m.addEdge(l, rnd.nextInt(n));
            }
        }
        int[] size = new int[1];
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> size[0] = m.solve());
        assertTrue(size[0] > n * 9 / 10, "size " + size[0]);
    }

    @Test
    void agreesWithFlowNetworkOnUnitCapacities() {
        Random rnd = new Random(9);
        for (int trial = 0; trial < 200; trial++) {
            int nl = 1 + rnd.nextInt(7);
            int nr = 1 + rnd.nextInt(7);
            BipartiteMatching m = new BipartiteMatching(nl, nr);
            FlowNetwork g = new FlowNetwork(nl + nr + 2);
            for (int l = 0; l < nl; l++) {
                g.addEdge(0, 1 + l, 1);
            }
            for (int r = 0; r < nr; r++) {
                g.addEdge(1 + nl + r, 1 + nl + nr, 1);
            }
            for (int i = rnd.nextInt(nl * nr + 1); i > 0; i--) {
                int l = rnd.nextInt(nl);
                int r = rnd.nextInt(nr);
                m.addEdge(l, r);
                g.addEdge(1 + l, 1 + nl + r, 1);
            }
            assertEquals(g.maxFlow(0, nl + nr + 1), m.solve(), "trial " + trial);
        }
    }
}
