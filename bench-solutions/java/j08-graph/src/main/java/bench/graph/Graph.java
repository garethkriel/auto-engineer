package bench.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Dijkstra shortest path. */
public final class Graph {
    private Graph() { }

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) {
            throw new IllegalArgumentException("null argument");
        }
        for (Map.Entry<String, Map<String, Integer>> e : edges.entrySet()) {
            if (e.getValue() == null) {
                continue;
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
        PriorityQueue<Object[]> pq = new PriorityQueue<>((a, b) -> Long.compare((Long) a[1], (Long) b[1]));
        dist.put(from, 0L);
        pq.add(new Object[] {from, 0L});
        while (!pq.isEmpty()) {
            Object[] cur = pq.poll();
            String u = (String) cur[0];
            long du = (Long) cur[1];
            if (du > dist.get(u)) {
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
                long nd = du + e.getValue();
                Long old = dist.get(e.getKey());
                if (old == null || nd < old) {
                    dist.put(e.getKey(), nd);
                    prev.put(e.getKey(), u);
                    pq.add(new Object[] {e.getKey(), nd});
                }
            }
        }
        if (!dist.containsKey(to)) {
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
