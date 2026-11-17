package bench.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Dijkstra shortest path over a weighted directed graph. */
public final class Graph {
    private Graph() { }

    private record Item(String node, long dist) { }

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) {
            throw new IllegalArgumentException("null argument");
        }
        for (Map<String, Integer> out : edges.values()) {
            if (out == null) {
                throw new IllegalArgumentException("null adjacency");
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
        PriorityQueue<Item> pq = new PriorityQueue<>((a, b) -> Long.compare(a.dist(), b.dist()));
        dist.put(from, 0L);
        pq.add(new Item(from, 0L));
        while (!pq.isEmpty()) {
            Item it = pq.poll();
            if (it.dist() > dist.get(it.node())) {
                continue;
            }
            if (it.node().equals(to)) {
                break;
            }
            Map<String, Integer> out = edges.get(it.node());
            if (out == null) {
                continue;
            }
            for (Map.Entry<String, Integer> e : out.entrySet()) {
                long nd = it.dist() + e.getValue();
                Long old = dist.get(e.getKey());
                if (old == null || nd < old) {
                    dist.put(e.getKey(), nd);
                    prev.put(e.getKey(), it.node());
                    pq.add(new Item(e.getKey(), nd));
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
