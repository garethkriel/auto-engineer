package ae.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Directed flow network with {@code long} capacities and costs.
 *
 * <p>Arcs live in paired arrays: arc {@code 2i} is edge {@code i}, arc {@code 2i+1} its residual twin.
 * Traversals are iterative, so very long chains are safe.
 */
public final class FlowNetwork {

    /** Largest accepted capacity. */
    public static final long MAX_CAPACITY = Long.MAX_VALUE / 4;
    /** Largest accepted absolute cost. */
    public static final long MAX_COST = 1_000_000_000_000L;

    /** Flow value and total cost of a min-cost-flow run. */
    public record FlowResult(long flow, long cost) {}

    private final int n;
    private final int[] head;
    private int[] to = new int[16];
    private int[] next = new int[16];
    private long[] res = new long[16];
    private long[] cap = new long[16];
    private long[] cost = new long[16];
    private int arcs;

    /** Creates a network with {@code nodes} nodes numbered {@code 0..nodes-1}. */
    public FlowNetwork(int nodes) {
        if (nodes <= 0) {
            throw new IllegalArgumentException("nodes must be positive: " + nodes);
        }
        this.n = nodes;
        this.head = new int[nodes];
        Arrays.fill(head, -1);
    }

    public int nodeCount() {
        return n;
    }

    public int edgeCount() {
        return arcs / 2;
    }

    public int addEdge(int from, int toNode, long capacity) {
        return addEdge(from, toNode, capacity, 0);
    }

    /** Adds an edge and returns its id (ids count up from 0 in insertion order). */
    public int addEdge(int from, int toNode, long capacity, long unitCost) {
        checkNode(from);
        checkNode(toNode);
        if (from == toNode) {
            throw new IllegalArgumentException("self-loop at " + from);
        }
        if (capacity < 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity out of range: " + capacity);
        }
        if (Math.abs(unitCost) > MAX_COST) {
            throw new IllegalArgumentException("cost out of range: " + unitCost);
        }
        if (arcs + 2 > to.length) {
            int size = to.length * 2;
            to = Arrays.copyOf(to, size);
            next = Arrays.copyOf(next, size);
            res = Arrays.copyOf(res, size);
            cap = Arrays.copyOf(cap, size);
            cost = Arrays.copyOf(cost, size);
        }
        int e = arcs;
        arc(e, toNode, capacity, unitCost, head[from]);
        head[from] = e;
        arc(e + 1, from, 0, -unitCost, head[toNode]);
        head[toNode] = e + 1;
        arcs += 2;
        return e / 2;
    }

    private void arc(int e, int target, long capacity, long unitCost, int nextArc) {
        to[e] = target;
        res[e] = capacity;
        cap[e] = capacity;
        cost[e] = unitCost;
        next[e] = nextArc;
    }

    private void checkNode(int v) {
        if (v < 0 || v >= n) {
            throw new IllegalArgumentException("node out of range: " + v);
        }
    }

    private void checkEdge(int id) {
        if (id < 0 || id >= edgeCount()) {
            throw new IllegalArgumentException("edge out of range: " + id);
        }
    }

    public int from(int id) {
        checkEdge(id);
        return to[2 * id + 1];
    }

    public int to(int id) {
        checkEdge(id);
        return to[2 * id];
    }

    public long capacity(int id) {
        checkEdge(id);
        return cap[2 * id];
    }

    public long flow(int id) {
        checkEdge(id);
        return cap[2 * id] - res[2 * id];
    }

    /** Removes all flow. */
    public void reset() {
        for (int e = 0; e < arcs; e += 2) {
            res[e] = cap[e];
            res[e + 1] = 0;
        }
    }

    private void checkTerminals(int s, int t) {
        checkNode(s);
        checkNode(t);
        if (s == t) {
            throw new IllegalArgumentException("source equals sink: " + s);
        }
    }

    private long capacityLeaving(int s) {
        long sum = 0;
        for (int e = head[s]; e != -1; e = next[e]) {
            if ((e & 1) == 0) {
                sum = Math.addExact(sum, cap[e]);
            }
        }
        return sum;
    }

    /**
     * Dinic's algorithm. Returns the additional flow pushed (a second call returns 0).
     * The sum of capacities leaving {@code s} must fit in a long; otherwise
     * {@link ArithmeticException} is thrown before anything changes.
     */
    public long maxFlow(int s, int t) {
        checkTerminals(s, t);
        capacityLeaving(s);
        int[] level = new int[n];
        int[] it = new int[n];
        int[] path = new int[n];
        int[] queue = new int[n];
        long total = 0;
        while (buildLevels(s, t, level, queue)) {
            System.arraycopy(head, 0, it, 0, n);
            long pushed;
            while ((pushed = augment(s, t, level, it, path)) > 0) {
                total = Math.addExact(total, pushed);
            }
        }
        return total;
    }

    private boolean buildLevels(int s, int t, int[] level, int[] queue) {
        Arrays.fill(level, -1);
        int qh = 0;
        int qt = 0;
        queue[qt++] = s;
        level[s] = 0;
        while (qh < qt) {
            int u = queue[qh++];
            for (int e = head[u]; e != -1; e = next[e]) {
                int v = to[e];
                if (res[e] > 0 && level[v] < 0) {
                    level[v] = level[u] + 1;
                    queue[qt++] = v;
                }
            }
        }
        return level[t] >= 0;
    }

    /** Finds one s-t path in the level graph, pushes its bottleneck, returns it (0 if none). */
    private long augment(int s, int t, int[] level, int[] it, int[] path) {
        int depth = 0;
        int u = s;
        while (true) {
            if (u == t) {
                long b = Long.MAX_VALUE;
                for (int i = 0; i < depth; i++) {
                    b = Math.min(b, res[path[i]]);
                }
                for (int i = 0; i < depth; i++) {
                    res[path[i]] -= b;
                    res[path[i] ^ 1] += b;
                }
                return b;
            }
            boolean advanced = false;
            for (; it[u] != -1; it[u] = next[it[u]]) {
                int e = it[u];
                int v = to[e];
                if (res[e] > 0 && level[v] == level[u] + 1) {
                    path[depth++] = e;
                    u = v;
                    advanced = true;
                    break;
                }
            }
            if (!advanced) {
                if (u == s) {
                    return 0;
                }
                level[u] = -1; // dead end
                depth--;
                u = to[path[depth] ^ 1];
                it[u] = next[it[u]];
            }
        }
    }

    /** After {@link #maxFlow}: nodes reachable from {@code s} in the residual graph (the minimal min cut). */
    public boolean[] minCut(int s) {
        checkNode(s);
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int qh = 0;
        int qt = 0;
        queue[qt++] = s;
        seen[s] = true;
        while (qh < qt) {
            int u = queue[qh++];
            for (int e = head[u]; e != -1; e = next[e]) {
                if (res[e] > 0 && !seen[to[e]]) {
                    seen[to[e]] = true;
                    queue[qt++] = to[e];
                }
            }
        }
        return seen;
    }

    /** Ids of edges that go from the source side of {@link #minCut} to the sink side, ascending. */
    public List<Integer> cutEdges(int s) {
        boolean[] side = minCut(s);
        List<Integer> out = new ArrayList<>();
        for (int id = 0; id < edgeCount(); id++) {
            if (side[to[2 * id + 1]] && !side[to[2 * id]]) {
                out.add(id);
            }
        }
        return out;
    }

    /** Total cost of the current flow. */
    public long totalCost() {
        long sum = 0;
        for (int e = 0; e < arcs; e += 2) {
            sum = Math.addExact(sum, Math.multiplyExact(cap[e] - res[e], cost[e]));
        }
        return sum;
    }

    /**
     * Min-cost flow by successive shortest paths (SPFA, so negative costs are fine). The result has value
     * {@code min(limit, maxflow)} and minimum cost among such flows.
     *
     * @throws IllegalStateException if the network already carries flow or has a negative-cost residual cycle
     * @throws ArithmeticException if a cost overflows a long; the network is reset first
     */
    public FlowResult minCostFlow(int s, int t, long limit) {
        checkTerminals(s, t);
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0: " + limit);
        }
        for (int e = 0; e < arcs; e += 2) {
            if (res[e] != cap[e]) {
                throw new IllegalStateException("network already carries flow; call reset() first");
            }
        }
        if (hasNegativeResidualCycle()) {
            throw new IllegalStateException("negative-cost cycle");
        }
        capacityLeaving(s);
        try {
            return successiveShortestPaths(s, t, limit);
        } catch (ArithmeticException ex) {
            reset();
            throw ex;
        }
    }

    private FlowResult successiveShortestPaths(int s, int t, long limit) {
        long flow = 0;
        long total = 0;
        long[] dist = new long[n];
        int[] prev = new int[n];
        boolean[] queued = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        while (flow < limit) {
            Arrays.fill(dist, Long.MAX_VALUE);
            Arrays.fill(prev, -1);
            dist[s] = 0;
            queue.add(s);
            queued[s] = true;
            while (!queue.isEmpty()) {
                int u = queue.poll();
                queued[u] = false;
                for (int e = head[u]; e != -1; e = next[e]) {
                    int v = to[e];
                    if (res[e] > 0 && dist[u] + cost[e] < dist[v]) {
                        dist[v] = dist[u] + cost[e];
                        prev[v] = e;
                        if (!queued[v]) {
                            queued[v] = true;
                            queue.add(v);
                        }
                    }
                }
            }
            if (dist[t] == Long.MAX_VALUE) {
                break;
            }
            long b = limit - flow;
            for (int v = t; v != s; v = to[prev[v] ^ 1]) {
                b = Math.min(b, res[prev[v]]);
            }
            for (int v = t; v != s; v = to[prev[v] ^ 1]) {
                res[prev[v]] -= b;
                res[prev[v] ^ 1] += b;
            }
            flow = Math.addExact(flow, b);
            total = Math.addExact(total, Math.multiplyExact(b, dist[t]));
        }
        return new FlowResult(flow, total);
    }

    /**
     * True if the residual graph has a negative-cost cycle. For a feasible flow, "false" certifies that it is a
     * minimum-cost flow of its value.
     */
    public boolean hasNegativeResidualCycle() {
        long[] dist = new long[n]; // all zero: virtual source reaching every node
        for (int round = 0; round < n; round++) {
            boolean changed = false;
            for (int e = 0; e < arcs; e++) {
                if (res[e] > 0) {
                    int u = to[e ^ 1];
                    if (dist[u] + cost[e] < dist[to[e]]) {
                        dist[to[e]] = dist[u] + cost[e];
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return false;
            }
        }
        return true;
    }

    /** Checks capacity bounds and conservation; returns human-readable violations (empty if valid). */
    public List<String> verify(int s, int t) {
        checkTerminals(s, t);
        List<String> problems = new ArrayList<>();
        long[] net = new long[n];
        for (int id = 0; id < edgeCount(); id++) {
            long f = flow(id);
            if (f < 0 || f > cap[2 * id]) {
                problems.add("edge " + id + " flow " + f + " outside [0," + cap[2 * id] + "]");
            }
            net[to[2 * id + 1]] += f;
            net[to[2 * id]] -= f;
        }
        for (int v = 0; v < n; v++) {
            if (v != s && v != t && net[v] != 0) {
                problems.add("node " + v + " not conserved: net " + net[v]);
            }
        }
        if (net[s] + net[t] != 0) {
            problems.add("source outflow " + net[s] + " != sink inflow " + (-net[t]));
        }
        return problems;
    }
}
