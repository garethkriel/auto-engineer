package bench.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Dijkstra shortest path over a weighted directed graph. */
public final class Graph {
    private Graph() {
    }

    private record Entry(String node, long dist) {
    }

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) {
            throw new IllegalArgumentException("null argument");
        }
        for (Map<String, Integer> out : edges.values()) {
            if (out == null) {
                continue;
            }
            for (Integer w : out.values()) {
                if (w == null || w < 0) {
                    throw new IllegalArgumentException("negative or null weight");
                }
            }
        }
        List<String> result = new ArrayList<>();
        if (from.equals(to)) {
            result.add(from);
            return result;
        }
        Map<String, Long> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Map<String, Boolean> done = new HashMap<>();
        PriorityQueue<Entry> pq = new PriorityQueue<>((a, b) -> Long.compare(a.dist, b.dist));
        dist.put(from, 0L);
        pq.add(new Entry(from, 0L));
        while (!pq.isEmpty()) {
            Entry e = pq.poll();
            if (done.put(e.node, Boolean.TRUE) != null) {
                continue;
            }
            if (e.node.equals(to)) {
                break;
            }
            Map<String, Integer> out = edges.get(e.node);
            if (out == null) {
                continue;
            }
            for (Map.Entry<String, Integer> ed : out.entrySet()) {
                String v = ed.getKey();
                long nd = e.dist + ed.getValue();
                Long cur = dist.get(v);
                if (cur == null || nd < cur) {
                    dist.put(v, nd);
                    prev.put(v, e.node);
                    pq.add(new Entry(v, nd));
                }
            }
        }
        if (!dist.containsKey(to)) {
            return result;
        }
        for (String n = to; n != null; n = prev.get(n)) {
            result.add(n);
        }
        Collections.reverse(result);
        return result;
    }
}
