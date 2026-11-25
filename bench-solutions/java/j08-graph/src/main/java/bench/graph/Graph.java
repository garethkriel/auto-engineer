package bench.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Dijkstra shortest path. */
public final class Graph {
    private Graph() {}

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) {
            throw new IllegalArgumentException("null argument");
        }
        for (Map<String, Integer> out : edges.values()) {
            if (out == null) {
                throw new IllegalArgumentException("null adjacency map");
            }
            for (Integer w : out.values()) {
                if (w == null || w < 0) {
                    throw new IllegalArgumentException("invalid weight");
                }
            }
        }
        if (from.equals(to)) {
            return new ArrayList<>(List.of(from));
        }
        Map<String, Long> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>((x, y) -> Long.compare((Long) x[0], (Long) y[0]));
        dist.put(from, 0L);
        pq.add(new Object[] {0L, from});
        while (!pq.isEmpty()) {
            Object[] top = pq.poll();
            long d = (Long) top[0];
            String u = (String) top[1];
            if (d > dist.get(u)) {
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
                long nd = d + e.getValue();
                Long old = dist.get(e.getKey());
                if (old == null || nd < old) {
                    dist.put(e.getKey(), nd);
                    prev.put(e.getKey(), u);
                    pq.add(new Object[] {nd, e.getKey()});
                }
            }
        }
        if (!dist.containsKey(to)) {
            return new ArrayList<>();
        }
        List<String> path = new ArrayList<>();
        for (String at = to; at != null; at = prev.get(at)) {
            path.add(at);
        }
        Collections.reverse(path);
        return path;
    }
}
