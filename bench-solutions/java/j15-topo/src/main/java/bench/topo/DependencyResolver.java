package bench.topo;

import java.util.*;

public final class DependencyResolver {
    private DependencyResolver() {}

    public static List<String> order(Map<String, Set<String>> dependsOn) {
        if (dependsOn == null) throw new IllegalArgumentException("null map");
        Map<String, Set<String>> deps = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) throw new IllegalArgumentException("null");
            Set<String> d = new HashSet<>();
            for (String x : e.getValue()) {
                if (x == null) throw new IllegalArgumentException("null node");
                d.add(x);
            }
            deps.put(e.getKey(), d);
        }
        Set<String> nodes = new HashSet<>(deps.keySet());
        for (Set<String> d : deps.values()) nodes.addAll(d);
        Map<String, Integer> indeg = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (String n : nodes) indeg.put(n, 0);
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            indeg.put(e.getKey(), e.getValue().size());
            for (String d : e.getValue()) dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(e.getKey());
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (String n : nodes) if (indeg.get(n) == 0) ready.add(n);
        List<String> out = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String n = ready.poll();
            out.add(n);
            for (String m : dependents.getOrDefault(n, List.of())) {
                if (indeg.merge(m, -1, Integer::sum) == 0) ready.add(m);
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
            for (String d : deps.get(cur)) if (!done.contains(d)) { next = d; break; }
            cur = next;
        }
        List<String> cycle = new ArrayList<>(path.subList(seen.get(cur), path.size()));
        cycle.add(cur);
        throw new CycleException(cycle);
    }
}
