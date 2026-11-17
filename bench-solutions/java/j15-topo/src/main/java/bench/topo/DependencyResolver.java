package bench.topo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/** Lexicographically smallest topological order (Kahn with a min-heap). */
public final class DependencyResolver {
    private DependencyResolver() {}

    public static List<String> order(Map<String, Set<String>> dependsOn) {
        if (dependsOn == null) {
            throw new IllegalArgumentException("null map");
        }
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("null key or set");
            }
            Set<String> copy = new HashSet<>();
            for (String d : e.getValue()) {
                if (d == null) {
                    throw new IllegalArgumentException("null node");
                }
                copy.add(d);
            }
            deps.put(e.getKey(), copy);
        }
        for (Set<String> ds : new ArrayList<>(deps.values())) {
            for (String d : ds) {
                deps.putIfAbsent(d, new HashSet<>());
            }
        }
        Map<String, Integer> remaining = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            remaining.put(e.getKey(), e.getValue().size());
            for (String d : e.getValue()) {
                dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (Map.Entry<String, Integer> e : remaining.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<String> out = new ArrayList<>(deps.size());
        while (!ready.isEmpty()) {
            String n = ready.poll();
            out.add(n);
            for (String m : dependents.getOrDefault(n, List.of())) {
                if (remaining.merge(m, -1, Integer::sum) == 0) {
                    ready.add(m);
                }
            }
        }
        if (out.size() == deps.size()) {
            return out;
        }
        Set<String> done = new HashSet<>(out);
        String start = null;
        for (String n : new TreeSet<>(deps.keySet())) {
            if (!done.contains(n)) {
                start = n;
                break;
            }
        }
        Map<String, Integer> index = new HashMap<>();
        List<String> path = new ArrayList<>();
        String cur = start;
        while (!index.containsKey(cur)) {
            index.put(cur, path.size());
            path.add(cur);
            String next = null;
            for (String d : new TreeSet<>(deps.get(cur))) {
                if (!done.contains(d)) {
                    next = d;
                    break;
                }
            }
            cur = next;
        }
        List<String> cycle = new ArrayList<>(path.subList(index.get(cur), path.size()));
        cycle.add(cur);
        throw new CycleException(Collections.unmodifiableList(cycle));
    }
}
