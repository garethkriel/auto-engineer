package bench.topo;

import java.util.*;

public final class DependencyResolver {
    private DependencyResolver() {}

    public static List<String> order(Map<String, Set<String>> dependsOn) {
        if (dependsOn == null) throw new IllegalArgumentException("null map");
        Map<String, Set<String>> deps = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) throw new IllegalArgumentException("null key or set");
            Set<String> copy = new HashSet<>();
            for (String d : e.getValue()) {
                if (d == null) throw new IllegalArgumentException("null node");
                copy.add(d);
            }
            deps.put(e.getKey(), copy);
        }
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> indeg = new HashMap<>();
        Set<String> nodes = new HashSet<>(deps.keySet());
        for (Set<String> ds : deps.values()) nodes.addAll(ds);
        for (String n : nodes) indeg.put(n, 0);
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            for (String d : e.getValue()) {
                dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(e.getKey());
                indeg.merge(e.getKey(), 1, Integer::sum);
            }
        }
        PriorityQueue<String> pq = new PriorityQueue<>();
        for (String n : nodes) if (indeg.get(n) == 0) pq.add(n);
        List<String> out = new ArrayList<>(nodes.size());
        while (!pq.isEmpty()) {
            String n = pq.poll();
            out.add(n);
            for (String m : dependents.getOrDefault(n, List.of())) {
                if (indeg.merge(m, -1, Integer::sum) == 0) pq.add(m);
            }
        }
        if (out.size() == nodes.size()) return out;
        // find a cycle among the remaining nodes (each has an unresolved dependency)
        Set<String> done = new HashSet<>(out);
        String start = null;
        for (String n : nodes) if (!done.contains(n) && (start == null || n.compareTo(start) < 0)) start = n;
        Map<String, Integer> seen = new HashMap<>();
        List<String> path = new ArrayList<>();
        String cur = start;
        while (!seen.containsKey(cur)) {
            seen.put(cur, path.size());
            path.add(cur);
            String next = null;
            for (String d : deps.getOrDefault(cur, Set.of())) {
                if (!done.contains(d) && (next == null || d.compareTo(next) < 0)) next = d;
            }
            cur = next;
        }
        List<String> cycle = new ArrayList<>(path.subList(seen.get(cur), path.size()));
        cycle.add(cur);
        throw new CycleException(cycle);
    }
}
