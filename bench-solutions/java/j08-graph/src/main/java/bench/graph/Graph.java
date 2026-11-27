package bench.graph;

import java.util.*;

public final class Graph {
    private Graph() {}

    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to) {
        if (edges == null || from == null || to == null) throw new IllegalArgumentException("null");
        for (Map.Entry<String, Map<String, Integer>> e : edges.entrySet()) {
            if (e.getValue() == null) throw new IllegalArgumentException("null edge map");
            for (Map.Entry<String, Integer> w : e.getValue().entrySet())
                if (w.getKey() == null || w.getValue() == null || w.getValue() < 0)
                    throw new IllegalArgumentException("bad edge");
        }
        if (from.equals(to)) return new ArrayList<>(List.of(from));
        Map<String, Long> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> done = new HashSet<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>(Comparator.comparingLong(o -> (Long) o[0]));
        dist.put(from, 0L);
        pq.add(new Object[]{0L, from});
        while (!pq.isEmpty()) {
            Object[] top = pq.poll();
            long d = (Long) top[0];
            String u = (String) top[1];
            if (!done.add(u)) continue;
            if (u.equals(to)) break;
            Map<String, Integer> out = edges.get(u);
            if (out == null) continue;
            for (Map.Entry<String, Integer> e : out.entrySet()) {
                String v = e.getKey();
                long nd = d + e.getValue();
                Long cur = dist.get(v);
                if (cur == null || nd < cur) {
                    dist.put(v, nd);
                    prev.put(v, u);
                    pq.add(new Object[]{nd, v});
                }
            }
        }
        if (!dist.containsKey(to)) return new ArrayList<>();
        LinkedList<String> path = new LinkedList<>();
        for (String n = to; n != null; n = prev.get(n)) path.addFirst(n);
        return new ArrayList<>(path);
    }
}
