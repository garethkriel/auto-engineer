package bench.topo;

import java.util.*;

public final class DependencyResolver {
    private DependencyResolver() {}

    public static List<String> order(Map<String, Set<String>> dependsOn) {
        if (dependsOn == null) throw new IllegalArgumentException("null map");
        Map<String, Set<String>> deps = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) throw new IllegalArgumentException("null key or set");
            Set<String> s = deps.computeIfAbsent(e.getKey(), k -> new HashSet<>());
            for (String d : e.getValue()) {
                if (d == null) throw new IllegalArgumentException("null node");
                s.add(d);
            }
        }
        for (Set<String> s : new ArrayList<>(deps.values())) {
            for (String d : s) deps.computeIfAbsent(d, k -> new HashSet<>());
        }
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> indeg = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            indeg.put(e.getKey(), e.getValue().size());
            for (String d : e.getValue()) dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(e.getKey());
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (Map.Entry<String, Integer> e : indeg.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());
        List<String> out = new ArrayList<>(deps.size());
        Set<String> done = new HashSet<>();
        while (!ready.isEmpty()) {
            String n = ready.poll();
            out.add(n);
            done.add(n);
            for (String m : dependents.getOrDefault(n, List.of())) {
                if (indeg.merge(m, -1, Integer::sum) == 0) ready.add(m);
            }
        }
        if (out.size() == deps.size()) return out;
        // Every unprocessed node has an unprocessed dependency; walk until a repeat.
        String start = null;
        for (String n : deps.keySet()) {
            if (!done.contains(n) && (start == null || n.compareTo(start) < 0)) start = n;
        }
        List<String> path = new ArrayList<>();
        Map<String, Integer> pos = new HashMap<>();
        String cur = start;
        while (!pos.containsKey(cur)) {
            pos.put(cur, path.size());
            path.add(cur);
            String next = null;
            for (String d : deps.get(cur)) {
                if (!done.contains(d) && (next == null || d.compareTo(next) < 0)) next = d;
            }
            cur = next;
        }
        List<String> cycle = new ArrayList<>(path.subList(pos.get(cur), path.size()));
        cycle.add(cur);
        throw new CycleException(cycle);
    }
}
