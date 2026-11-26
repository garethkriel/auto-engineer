package bench.graph;

import java.util.*;

/** Dijkstra shortest path. */
public final class Graph {
    private Graph() {}

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) throw new IllegalArgumentException("null argument");
        for (Map.Entry<String, Map<String, Integer>> e : edges.entrySet()) {
            if (e.getValue() == null) throw new IllegalArgumentException("null adjacency");
            for (Map.Entry<String, Integer> w : e.getValue().entrySet()) {
                if (w.getValue() == null || w.getValue() < 0 || w.getKey() == null)
                    throw new IllegalArgumentException("bad edge");
            }
        }
        if (from.equals(to)) return new ArrayList<>(List.of(from));
        Map<String, Long> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> done = new HashSet<>();
        PriorityQueue<Map.Entry<String, Long>> pq =
                new PriorityQueue<>(Map.Entry.comparingByValue());
        dist.put(from, 0L);
        pq.add(Map.entry(from, 0L));
        while (!pq.isEmpty()) {
            Map.Entry<String, Long> cur = pq.poll();
            String u = cur.getKey();
            if (!done.add(u)) continue;
            if (u.equals(to)) break;
            Map<String, Integer> out = edges.get(u);
            if (out == null) continue;
            for (Map.Entry<String, Integer> e : out.entrySet()) {
                String v = e.getKey();
                long nd = cur.getValue() + e.getValue();
                Long old = dist.get(v);
                if (old == null || nd < old) {
                    dist.put(v, nd);
                    prev.put(v, u);
                    pq.add(Map.entry(v, nd));
                }
            }
        }
        if (!done.contains(to)) return new ArrayList<>();
        LinkedList<String> path = new LinkedList<>();
        for (String n = to; n != null; n = prev.get(n)) path.addFirst(n);
        return new ArrayList<>(path);
    }
}
