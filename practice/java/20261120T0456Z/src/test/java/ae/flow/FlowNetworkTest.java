package ae.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FlowNetworkTest {

    private static FlowNetwork clrs() {
        // s=0 v1=1 v2=2 v3=3 v4=4 t=5 ; edge ids in insertion order 0..8
        FlowNetwork g = new FlowNetwork(6);
        g.addEdge(0, 1, 16);
        g.addEdge(0, 2, 13);
        g.addEdge(1, 3, 12);
        g.addEdge(2, 1, 4);
        g.addEdge(2, 4, 14);
        g.addEdge(3, 2, 9);
        g.addEdge(3, 5, 20);
        g.addEdge(4, 3, 7);
        g.addEdge(4, 5, 4);
        return g;
    }

    @Test
    void clrsMaxFlowIs23AndSecondCallPushesNothing() {
        FlowNetwork g = clrs();
        assertEquals(23, g.maxFlow(0, 5));
        assertEquals(List.of(), g.verify(0, 5));
        assertEquals(0, g.maxFlow(0, 5));
    }

    @Test
    void clrsMinCutIsTheThreeSaturatedEdges() {
        FlowNetwork g = clrs();
        g.maxFlow(0, 5);
        boolean[] side = g.minCut(0);
        assertArrayEquals(new boolean[] {true, true, true, false, true, false}, side);
        assertEquals(List.of(2, 7, 8), g.cutEdges(0));
    }

    @Test
    void disconnectedAndZeroCapacityGiveZero() {
        FlowNetwork g = new FlowNetwork(4);
        g.addEdge(0, 1, 5);
        g.addEdge(2, 3, 5);
        assertEquals(0, g.maxFlow(0, 3));
        FlowNetwork h = new FlowNetwork(2);
        h.addEdge(0, 1, 0);
        assertEquals(0, h.maxFlow(0, 1));
    }

    @Test
    void parallelEdgesAddUp() {
        FlowNetwork g = new FlowNetwork(2);
        int a = g.addEdge(0, 1, 3);
        int b = g.addEdge(0, 1, 4);
        assertEquals(7, g.maxFlow(0, 1));
        assertEquals(3, g.flow(a));
        assertEquals(4, g.flow(b));
        assertEquals(0, g.from(a));
        assertEquals(1, g.to(a));
        assertEquals(3, g.capacity(a));
    }

    @Test
    void resetClearsFlow() {
        FlowNetwork g = clrs();
        g.maxFlow(0, 5);
        g.reset();
        for (int i = 0; i < g.edgeCount(); i++) {
            assertEquals(0, g.flow(i));
        }
        assertEquals(23, g.maxFlow(0, 5));
    }

    /** Independent oracle: Edmonds-Karp on an adjacency matrix. */
    private static long oracle(long[][] c, int s, int t) {
        int n = c.length;
        long[][] r = new long[n][];
        for (int i = 0; i < n; i++) {
            r[i] = c[i].clone();
        }
        long total = 0;
        while (true) {
            int[] prev = new int[n];
            java.util.Arrays.fill(prev, -1);
            prev[s] = s;
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(s);
            while (!q.isEmpty() && prev[t] < 0) {
                int u = q.poll();
                for (int v = 0; v < n; v++) {
                    if (prev[v] < 0 && r[u][v] > 0) {
                        prev[v] = u;
                        q.add(v);
                    }
                }
            }
            if (prev[t] < 0) {
                return total;
            }
            long b = Long.MAX_VALUE;
            for (int v = t; v != s; v = prev[v]) {
                b = Math.min(b, r[prev[v]][v]);
            }
            for (int v = t; v != s; v = prev[v]) {
                r[prev[v]][v] -= b;
                r[v][prev[v]] += b;
            }
            total += b;
        }
    }

    @Test
    void randomGraphsMatchEdmondsKarpAndCutEqualsFlow() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 400; trial++) {
            int n = 2 + rnd.nextInt(7);
            long[][] c = new long[n][n];
            FlowNetwork g = new FlowNetwork(n);
            int edges = rnd.nextInt(3 * n);
            for (int i = 0; i < edges; i++) {
                int u = rnd.nextInt(n);
                int v = rnd.nextInt(n);
                if (u == v) {
                    continue;
                }
                long cap = rnd.nextInt(10);
                c[u][v] += cap;
                g.addEdge(u, v, cap);
            }
            int s = 0;
            int t = n - 1;
            long expected = oracle(c, s, t);
            assertEquals(expected, g.maxFlow(s, t), "trial " + trial);
            assertEquals(List.of(), g.verify(s, t), "trial " + trial);
            long cut = 0;
            for (int id : g.cutEdges(s)) {
                cut += g.capacity(id);
            }
            assertEquals(expected, cut, "cut, trial " + trial);
            assertTrue(g.minCut(s)[s]);
            assertFalse(g.minCut(s)[t]);
        }
    }

    @Test
    void chainOf200000NodesDoesNotOverflowTheStack() {
        int n = 200_000;
        FlowNetwork g = new FlowNetwork(n);
        for (int i = 0; i + 1 < n; i++) {
            g.addEdge(i, i + 1, i == 123_456 ? 7 : 1000);
        }
        assertEquals(7, g.maxFlow(0, n - 1));
    }

    @Test
    void rejectsBadArguments() {
        FlowNetwork g = new FlowNetwork(3);
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(0, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(0, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(0, 1, FlowNetwork.MAX_CAPACITY + 1));
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(0, 1, 1, FlowNetwork.MAX_COST + 1));
        assertThrows(IllegalArgumentException.class, () -> g.maxFlow(1, 1));
        assertThrows(IllegalArgumentException.class, () -> g.maxFlow(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new FlowNetwork(0));
        assertEquals(0, g.edgeCount());
    }

    @Test
    void capacityOverflowFailsBeforeMutating() {
        FlowNetwork g = new FlowNetwork(2);
        for (int i = 0; i < 5; i++) {
            g.addEdge(0, 1, FlowNetwork.MAX_CAPACITY);
        }
        assertThrows(ArithmeticException.class, () -> g.maxFlow(0, 1));
        for (int i = 0; i < 5; i++) {
            assertEquals(0, g.flow(i));
        }
    }

    // ---------- min cost ----------

    private static FlowNetwork costed() {
        FlowNetwork g = new FlowNetwork(4); // s=0 a=1 b=2 t=3
        g.addEdge(0, 1, 2, 1);
        g.addEdge(0, 2, 2, 4);
        g.addEdge(1, 2, 1, 1);
        g.addEdge(1, 3, 1, 6);
        g.addEdge(2, 3, 3, 1);
        return g;
    }

    @Test
    void minCostFlowForIncreasingLimits() {
        assertEquals(new FlowNetwork.FlowResult(2, 8), costed().minCostFlow(0, 3, 2));
        assertEquals(new FlowNetwork.FlowResult(3, 13), costed().minCostFlow(0, 3, 3));
        assertEquals(new FlowNetwork.FlowResult(4, 20), costed().minCostFlow(0, 3, Long.MAX_VALUE));
        assertEquals(new FlowNetwork.FlowResult(0, 0), costed().minCostFlow(0, 3, 0));
    }

    @Test
    void minCostFlowLeavesCertifiedNetwork() {
        FlowNetwork g = costed();
        g.minCostFlow(0, 3, Long.MAX_VALUE);
        assertEquals(List.of(), g.verify(0, 3));
        assertFalse(g.hasNegativeResidualCycle());
        assertEquals(20, g.totalCost());
    }

    @Test
    void minCostFlowRequiresZeroFlowNetwork() {
        FlowNetwork g = costed();
        g.maxFlow(0, 3);
        assertThrows(IllegalStateException.class, () -> g.minCostFlow(0, 3, 1));
    }

    @Test
    void negativeCycleIsDetectedAndRejected() {
        FlowNetwork g = new FlowNetwork(4);
        g.addEdge(0, 1, 1, 0);
        g.addEdge(1, 2, 1, -1);
        g.addEdge(2, 1, 1, -1);
        g.addEdge(2, 3, 1, 0);
        assertTrue(g.hasNegativeResidualCycle());
        assertThrows(IllegalStateException.class, () -> g.minCostFlow(0, 3, 1));
        FlowNetwork ok = new FlowNetwork(4);
        ok.addEdge(0, 1, 1, 0);
        ok.addEdge(1, 2, 1, -1);
        ok.addEdge(2, 1, 1, 1); // zero-weight cycle is fine
        ok.addEdge(2, 3, 1, 0);
        assertFalse(ok.hasNegativeResidualCycle());
        assertEquals(new FlowNetwork.FlowResult(1, -1), ok.minCostFlow(0, 3, 5));
    }

    @Test
    void costOverflowResetsNetworkAndThrows() {
        FlowNetwork g = new FlowNetwork(2);
        int id = g.addEdge(0, 1, 1_000_000_000_000_000L, FlowNetwork.MAX_COST);
        assertThrows(ArithmeticException.class, () -> g.minCostFlow(0, 1, Long.MAX_VALUE));
        assertEquals(0, g.flow(id));
    }

    /** Brute force: enumerate every integral flow, return {value, cost} of best for given limit. */
    private static long[] brute(int n, int[] u, int[] v, int[] cap, int[] cost, int s, int t, long limit) {
        int m = u.length;
        long bestValue = -1;
        long bestCost = 0;
        // first pass: max value
        int[] f = new int[m];
        long maxValue = 0;
        List<int[]> feasible = new ArrayList<>();
        while (true) {
            long[] net = new long[n];
            for (int i = 0; i < m; i++) {
                net[u[i]] += f[i];
                net[v[i]] -= f[i];
            }
            boolean ok = true;
            for (int x = 0; x < n; x++) {
                if (x != s && x != t && net[x] != 0) {
                    ok = false;
                }
            }
            if (ok && net[s] + net[t] == 0) {
                feasible.add(f.clone());
                maxValue = Math.max(maxValue, net[s]);
            }
            int k = 0;
            while (k < m && f[k] == cap[k]) {
                f[k++] = 0;
            }
            if (k == m) {
                break;
            }
            f[k]++;
        }
        long target = Math.min(limit, maxValue);
        for (int[] ff : feasible) {
            long value = 0;
            long c = 0;
            for (int i = 0; i < m; i++) {
                if (u[i] == s) {
                    value += ff[i];
                }
                if (v[i] == s) {
                    value -= ff[i];
                }
                c += (long) ff[i] * cost[i];
            }
            if (value == target && (bestValue < 0 || c < bestCost)) {
                bestValue = value;
                bestCost = c;
            }
        }
        return new long[] {target, bestCost};
    }

    private static void randomCostTrials(long seed, boolean dagWithNegatives) {
        Random rnd = new Random(seed);
        for (int trial = 0; trial < 150; trial++) {
            int n = 5;
            int m = 3 + rnd.nextInt(5);
            int[] u = new int[m];
            int[] v = new int[m];
            int[] cap = new int[m];
            int[] cost = new int[m];
            FlowNetwork g = new FlowNetwork(n);
            for (int i = 0; i < m; i++) {
                if (dagWithNegatives) {
                    u[i] = rnd.nextInt(n - 1);
                    v[i] = u[i] + 1 + rnd.nextInt(n - 1 - u[i]);
                    cost[i] = rnd.nextInt(9) - 3;
                } else {
                    do {
                        u[i] = rnd.nextInt(n);
                        v[i] = rnd.nextInt(n);
                    } while (u[i] == v[i]);
                    cost[i] = rnd.nextInt(6);
                }
                cap[i] = rnd.nextInt(3);
                g.addEdge(u[i], v[i], cap[i], cost[i]);
            }
            long limit = rnd.nextInt(5);
            long[] expected = brute(n, u, v, cap, cost, 0, n - 1, limit);
            FlowNetwork.FlowResult r = g.minCostFlow(0, n - 1, limit);
            String msg = "seed " + seed + " trial " + trial;
            assertEquals(expected[0], r.flow(), msg);
            assertEquals(expected[1], r.cost(), msg);
            assertEquals(List.of(), g.verify(0, n - 1), msg);
            assertFalse(g.hasNegativeResidualCycle(), msg);
            assertEquals(r.cost(), g.totalCost(), msg);
        }
    }

    @Test
    void minCostMatchesBruteForceOnDagsWithNegativeCosts() {
        randomCostTrials(11, true);
    }

    @Test
    void minCostMatchesBruteForceOnCyclicGraphs() {
        randomCostTrials(12, false);
    }
}
