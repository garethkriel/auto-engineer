package bench.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Dijkstra shortest path over a string-keyed weighted digraph. */
public final class Graph {
    private Graph() { }

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) {
            throw new IllegalArgumentException("null argument");
        }
        for (Map.Entry<String, Map<String, Integer>> e : edges.entrySet()) {
            if (e.getValue() == null) {
                throw new IllegalArgumentException("null edge map");
            }
            for (Map.Entry<String, Integer> w : e.getValue().entrySet()) {
                if (w.getValue() == null || w.getValue() < 0) {
                    throw new IllegalArgumentException("invalid weight");
                }
            }
        }
        if (from.equals(to)) {
            return new ArrayList<>(List.of(from));
        }
        Map<String, Long> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> done = new HashSet<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>((x, y) -> Long.compare((Long) x[0], (Long) y[0]));
        dist.put(from, 0L);
        pq.add(new Object[] {0L, from});
        while (!pq.isEmpty()) {
            Object[] top = pq.poll();
            long d = (Long) top[0];
            String u = (String) top[1];
            if (!done.add(u)) {
                continue;
            }
            if (u.equals(to)) {
                break;
            }
            Map<String, Integer> out = edges.get(u);
            if (out == null) {
                continue;
            }
            for (Map.Entry<String, Integer> e : out.entrySet()) {
                String v = e.getKey();
                long nd = d + e.getValue();
                Long cur = dist.get(v);
                if (cur == null || nd < cur) {
                    dist.put(v, nd);
                    prev.put(v, u);
                    pq.add(new Object[] {nd, v});
                }
            }
        }
        if (!dist.containsKey(to)) {
            return new ArrayList<>();
        }
        List<String> path = new ArrayList<>();
        for (String c = to; c != null; c = prev.get(c)) {
            path.add(c);
        }
        Collections.reverse(path);
        return path;
    }
}
