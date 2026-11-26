package bench.topo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Lexicographically smallest topological order. */
public final class DependencyResolver {
    private DependencyResolver() {}

    public static List<String> order(Map<String, Set<String>> dependsOn) {
        if (dependsOn == null) {
            throw new IllegalArgumentException("map is null");
        }
        Map<String, Set<String>> deps = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("null node or dependency set");
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
        Set<String> nodes = new HashSet<>(deps.keySet());
        for (Set<String> s : deps.values()) {
            nodes.addAll(s);
        }
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (String n : nodes) {
            Set<String> d = deps.getOrDefault(n, Set.of());
            remaining.put(n, d.size());
            for (String x : d) {
                dependents.computeIfAbsent(x, k -> new ArrayList<>()).add(n);
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (String n : nodes) {
            if (remaining.get(n) == 0) {
                ready.add(n);
            }
        }
        List<String> out = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String n = ready.poll();
            out.add(n);
            for (String m : dependents.getOrDefault(n, List.of())) {
                if (remaining.merge(m, -1, Integer::sum) == 0) {
                    ready.add(m);
                }
            }
        }
        if (out.size() == nodes.size()) {
            return out;
        }
        Set<String> done = new HashSet<>(out);
        String cur = null;
        for (String n : nodes) {
            if (!done.contains(n)) {
                cur = n;
                break;
            }
        }
        Map<String, Integer> pos = new HashMap<>();
        List<String> path = new ArrayList<>();
        while (!pos.containsKey(cur)) {
            pos.put(cur, path.size());
            path.add(cur);
            String next = null;
            for (String d : deps.getOrDefault(cur, Set.of())) {
                if (!done.contains(d)) {
                    next = d;
                    break;
                }
            }
            cur = next; // always non-null: an unprocessed node has an unprocessed dependency
        }
        List<String> cycle = new ArrayList<>(path.subList(pos.get(cur), path.size()));
        cycle.add(cur);
        throw new CycleException(Collections.unmodifiableList(cycle));
    }
}
