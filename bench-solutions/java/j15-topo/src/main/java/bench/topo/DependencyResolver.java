package bench.topo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Lexicographically smallest topological order (Kahn with a min-heap). */
public final class DependencyResolver {
    private DependencyResolver() {
    }

    public static List<String> order(Map<String, Set<String>> dependsOn) {
        if (dependsOn == null) {
            throw new IllegalArgumentException("null map");
        }
        Map<String, Set<String>> deps = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("null key or dependency set");
            }
            Set<String> copy = new HashSet<>();
            for (String d : e.getValue()) {
                if (d == null) {
                    throw new IllegalArgumentException("null node name");
                }
                copy.add(d);
            }
            deps.put(e.getKey(), copy);
        }
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> remaining = new HashMap<>();
        List<String> extra = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            remaining.put(e.getKey(), e.getValue().size());
            for (String d : e.getValue()) {
                dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(e.getKey());
                if (!deps.containsKey(d)) {
                    extra.add(d);
                }
            }
        }
        for (String n : extra) {
            deps.put(n, Set.of());
            remaining.put(n, 0);
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (Map.Entry<String, Integer> e : remaining.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<String> result = new ArrayList<>(deps.size());
        while (!ready.isEmpty()) {
            String n = ready.poll();
            result.add(n);
            List<String> ds = dependents.get(n);
            if (ds != null) {
                for (String x : ds) {
                    int r = remaining.merge(x, -1, Integer::sum);
                    if (r == 0) {
                        ready.add(x);
                    }
                }
            }
        }
        if (result.size() == deps.size()) {
            return result;
        }
        throw new CycleException(findCycle(deps, remaining));
    }

    private static List<String> findCycle(Map<String, Set<String>> deps, Map<String, Integer> remaining) {
        String start = null;
        for (Map.Entry<String, Integer> e : remaining.entrySet()) {
            if (e.getValue() > 0 && (start == null || e.getKey().compareTo(start) < 0)) {
                start = e.getKey();
            }
        }
        // every node with remaining > 0 has a dependency that also has remaining > 0
        Map<String, Integer> index = new HashMap<>();
        List<String> path = new ArrayList<>();
        String cur = start;
        while (!index.containsKey(cur)) {
            index.put(cur, path.size());
            path.add(cur);
            String next = null;
            for (String d : deps.get(cur)) {
                if (remaining.get(d) > 0 && (next == null || d.compareTo(next) < 0)) {
                    next = d;
                }
            }
            cur = next;
        }
        List<String> cycle = new ArrayList<>(path.subList(index.get(cur), path.size()));
        cycle.add(cur);
        return cycle;
    }
}
