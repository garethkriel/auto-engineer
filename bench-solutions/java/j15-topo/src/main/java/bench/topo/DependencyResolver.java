package bench.topo;

import java.util.*;

public final class DependencyResolver {
    private DependencyResolver() {}

    public static List<String> order(Map<String, Set<String>> dependsOn) {
        if (dependsOn == null) throw new IllegalArgumentException("null map");
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            String k = e.getKey();
            if (k == null) throw new IllegalArgumentException("null node");
            if (e.getValue() == null) throw new IllegalArgumentException("null deps");
            Set<String> ds = new HashSet<>();
            for (String d : e.getValue()) {
                if (d == null) throw new IllegalArgumentException("null node");
                ds.add(d);
            }
            deps.put(k, ds);
        }
        Set<String> nodes = new HashSet<>(deps.keySet());
        for (Set<String> ds : deps.values()) nodes.addAll(ds);
        Map<String, Integer> indeg = new HashMap<>();
        for (String n : nodes) {
            Set<String> ds = deps.getOrDefault(n, Set.of());
            indeg.put(n, ds.size());
            for (String d : ds) dependents.computeIfAbsent(d, x -> new ArrayList<>()).add(n);
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
            for (String d : deps.get(cur)) if (!done.contains(d) && (next == null || d.compareTo(next) < 0)) next = d;
            cur = next;
        }
        List<String> cyc = new ArrayList<>(path.subList(seen.get(cur), path.size()));
        cyc.add(cur);
        throw new CycleException(cyc);
    }
}
