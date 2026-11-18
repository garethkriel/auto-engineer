package bench.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Shortest path on a directed graph with non-negative integer weights (Dijkstra). */
public final class Graph {
    private Graph() {}

    private record Entry(String node, long dist) {}

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) {
            throw new IllegalArgumentException("null argument");
        }
        for (Map.Entry<String, Map<String, Integer>> e : edges.entrySet()) {
            Map<String, Integer> out = e.getValue();
            if (out == null) {
                continue;
            }
            for (Map.Entry<String, Integer> w : out.entrySet()) {
                if (w.getValue() == null || w.getValue() < 0) {
                    throw new IllegalArgumentException("invalid weight on edge " + e.getKey() + " -> " + w.getKey());
                }
            }
        }
        if (from.equals(to)) {
            return new ArrayList<>(List.of(from));
        }
        Map<String, Long> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> done = new HashSet<>();
        PriorityQueue<Entry> pq = new PriorityQueue<>((a, b) -> Long.compare(a.dist(), b.dist()));
        dist.put(from, 0L);
        pq.add(new Entry(from, 0L));
        while (!pq.isEmpty()) {
            Entry cur = pq.poll();
            if (!done.add(cur.node())) {
                continue;
            }
            if (cur.node().equals(to)) {
                break;
            }
            Map<String, Integer> out = edges.get(cur.node());
            if (out == null) {
                continue;
            }
            for (Map.Entry<String, Integer> w : out.entrySet()) {
                String v = w.getKey();
                if (done.contains(v)) {
                    continue;
                }
                long nd = cur.dist() + w.getValue();
                Long old = dist.get(v);
                if (old == null || nd < old) {
                    dist.put(v, nd);
                    prev.put(v, cur.node());
                    pq.add(new Entry(v, nd));
                }
            }
        }
        if (!done.contains(to)) {
            return new ArrayList<>();
        }
        List<String> path = new ArrayList<>();
        for (String n = to; n != null; n = prev.get(n)) {
            path.add(n);
        }
        Collections.reverse(path);
        return path;
    }
}
