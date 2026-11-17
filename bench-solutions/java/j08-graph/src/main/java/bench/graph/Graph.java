package bench.graph;

import java.util.*;

public final class Graph {
    private Graph() {}

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) throw new IllegalArgumentException("null");
        for (Map<String, Integer> m : edges.values()) {
            if (m == null) throw new IllegalArgumentException("null edges");
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue() < 0) {
                    throw new IllegalArgumentException("bad edge");
                }
            }
        }
        if (from.equals(to)) return new ArrayList<>(List.of(from));
        Map<String, Long> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> done = new HashSet<>();
        PriorityQueue<Map.Entry<String, Long>> pq = new PriorityQueue<>(Map.Entry.comparingByValue());
        dist.put(from, 0L);
        pq.add(Map.entry(from, 0L));
        while (!pq.isEmpty()) {
            var top = pq.poll();
            String u = top.getKey();
            if (!done.add(u)) continue;
            if (u.equals(to)) break;
            Map<String, Integer> out = edges.get(u);
            if (out == null) continue;
            for (Map.Entry<String, Integer> e : out.entrySet()) {
                long nd = top.getValue() + e.getValue();
                Long old = dist.get(e.getKey());
                if (old == null || nd < old) {
                    dist.put(e.getKey(), nd);
                    prev.put(e.getKey(), u);
                    pq.add(Map.entry(e.getKey(), nd));
                }
            }
        }
        if (!dist.containsKey(to)) return new ArrayList<>();
        LinkedList<String> path = new LinkedList<>();
        for (String c = to; c != null; c = prev.get(c)) path.addFirst(c);
        return new ArrayList<>(path);
    }
}
