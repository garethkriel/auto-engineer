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

    private record QE(String node, long dist) {}

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) throw new IllegalArgumentException("null");
        for (Map<String, Integer> m : edges.values()) {
            if (m == null) throw new IllegalArgumentException("null edge map");
            for (Integer w : m.values()) {
                if (w == null || w < 0) throw new IllegalArgumentException("negative weight");
            }
        }
        if (from.equals(to)) return new ArrayList<>(List.of(from));
        Map<String, Long> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        PriorityQueue<QE> pq = new PriorityQueue<>((a, b) -> Long.compare(a.dist(), b.dist()));
        dist.put(from, 0L);
        pq.add(new QE(from, 0L));
        while (!pq.isEmpty()) {
            QE cur = pq.poll();
            if (cur.dist() > dist.get(cur.node())) continue;
            if (cur.node().equals(to)) break;
            Map<String, Integer> out = edges.get(cur.node());
            if (out == null) continue;
            for (Map.Entry<String, Integer> e : out.entrySet()) {
                long nd = cur.dist() + e.getValue();
                Long old = dist.get(e.getKey());
                if (old == null || nd < old) {
                    dist.put(e.getKey(), nd);
                    prev.put(e.getKey(), cur.node());
                    pq.add(new QE(e.getKey(), nd));
                }
            }
        }
        if (!dist.containsKey(to)) return new ArrayList<>();
        List<String> path = new ArrayList<>();
        for (String n = to; n != null; n = prev.get(n)) path.add(n);
        Collections.reverse(path);
        return path;
    }
}
