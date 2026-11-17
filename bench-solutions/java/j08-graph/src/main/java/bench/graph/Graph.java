package bench.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Dijkstra shortest path. */
public final class Graph {
    private Graph() {}

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) {
            throw new IllegalArgumentException("null argument");
        }
        for (Map<String, Integer> out : edges.values()) {
            if (out == null) {
                continue;
            }
            for (Map.Entry<String, Integer> e : out.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue() < 0) {
                    throw new IllegalArgumentException("bad edge");
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
            long du = dist.get(u);
            for (Map.Entry<String, Integer> e : out.entrySet()) {
                long nd = du + e.getValue();
                Long cur = dist.get(e.getKey());
                if (cur == null || nd < cur) {
                    dist.put(e.getKey(), nd);
                    prev.put(e.getKey(), u);
                    pq.add(new Object[] {nd, e.getKey()});
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
