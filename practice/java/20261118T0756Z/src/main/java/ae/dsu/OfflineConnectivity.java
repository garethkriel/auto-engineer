package ae.dsu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline dynamic connectivity: edges are added and removed over time and connectivity is queried.
 * Each edge lifetime is an interval of event indexes; intervals are spread over a segment tree
 * on the timeline and a DFS applies them to a {@link RollbackDsu}, rolling back when leaving a node.
 */
public final class OfflineConnectivity {
    private OfflineConnectivity() {
    }

    /** One step of the timeline. */
    public sealed interface Event permits Add, Remove, Query {
    }

    public record Add(int u, int v) implements Event {
    }

    public record Remove(int u, int v) implements Event {
    }

    public record Query(int u, int v) implements Event {
    }

    private record Edge(int a, int b) {
        static Edge of(int u, int v) {
            return new Edge(Math.min(u, v), Math.max(u, v));
        }
    }

    private record Interval(Edge edge, int from, int to) {
    }

    /** Answers every {@link Query} in order. */
    public static List<Boolean> answer(int n, List<Event> events) {
        Objects.requireNonNull(events, "events");
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0: " + n);
        }
        int t = events.size();
        List<Interval> intervals = new ArrayList<>();
        Map<Edge, Deque<Integer>> open = new HashMap<>();
        List<Integer> queryTimes = new ArrayList<>();
        for (int i = 0; i < t; i++) {
            Event e = Objects.requireNonNull(events.get(i), "event " + i);
            switch (e) {
                case Add a -> {
                    validate(n, a.u(), a.v());
                    open.computeIfAbsent(Edge.of(a.u(), a.v()), k -> new ArrayDeque<>()).push(i);
                }
                case Remove r -> {
                    validate(n, r.u(), r.v());
                    Edge edge = Edge.of(r.u(), r.v());
                    Deque<Integer> starts = open.get(edge);
                    if (starts == null || starts.isEmpty()) {
                        throw new IllegalArgumentException("remove of absent edge at event " + i + ": " + r);
                    }
                    intervals.add(new Interval(edge, starts.pop(), i));
                }
                case Query q -> {
                    validate(n, q.u(), q.v());
                    queryTimes.add(i);
                }
            }
        }
        for (Map.Entry<Edge, Deque<Integer>> en : open.entrySet()) {
            for (int start : en.getValue()) {
                intervals.add(new Interval(en.getKey(), start, t));
            }
        }

        List<List<Edge>> tree = new ArrayList<>();
        int nodes = 4 * Math.max(1, t);
        for (int i = 0; i < nodes; i++) {
            tree.add(null);
        }
        for (Interval iv : intervals) {
            if (iv.from() < iv.to()) {
                insert(tree, 1, 0, t - 1, iv.from(), iv.to() - 1, iv.edge());
            }
        }
        Boolean[] byTime = new Boolean[t];
        if (t > 0) {
            solve(tree, 1, 0, t - 1, new RollbackDsu(n), events, byTime);
        }
        List<Boolean> out = new ArrayList<>(queryTimes.size());
        for (int qt : queryTimes) {
            out.add(byTime[qt]);
        }
        return out;
    }

    private static void validate(int n, int u, int v) {
        if (u < 0 || u >= n || v < 0 || v >= n) {
            throw new IllegalArgumentException("vertex out of range: " + u + "," + v);
        }
    }

    private static void insert(List<List<Edge>> tree, int node, int lo, int hi, int l, int r, Edge e) {
        if (l > hi || r < lo) {
            return;
        }
        if (l <= lo && hi <= r) {
            if (tree.get(node) == null) {
                tree.set(node, new ArrayList<>());
            }
            tree.get(node).add(e);
            return;
        }
        int mid = (lo + hi) >>> 1;
        insert(tree, node * 2, lo, mid, l, r, e);
        insert(tree, node * 2 + 1, mid + 1, hi, l, r, e);
    }

    private static void solve(List<List<Edge>> tree, int node, int lo, int hi, RollbackDsu dsu,
                              List<Event> events, Boolean[] byTime) {
        int mark = dsu.snapshot();
        List<Edge> here = tree.get(node);
        if (here != null) {
            for (Edge e : here) {
                dsu.union(e.a(), e.b());
            }
        }
        if (lo == hi) {
            if (events.get(lo) instanceof Query q) {
                byTime[lo] = dsu.connected(q.u(), q.v());
            }
        } else {
            int mid = (lo + hi) >>> 1;
            solve(tree, node * 2, lo, mid, dsu, events, byTime);
            solve(tree, node * 2 + 1, mid + 1, hi, dsu, events, byTime);
        }
        dsu.rollbackTo(mark);
    }
}
