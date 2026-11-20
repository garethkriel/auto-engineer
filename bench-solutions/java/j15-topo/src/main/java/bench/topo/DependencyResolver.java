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

public final class DependencyResolver {
    private DependencyResolver() {}

    public static List<String> order(Map<String, Set<String>> dependsOn) {
        if (dependsOn == null) throw new IllegalArgumentException("null map");
        Map<String, List<String>> deps = new HashMap<>();
        Set<String> nodes = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            String k = e.getKey();
            Set<String> v = e.getValue();
            if (k == null || v == null) throw new IllegalArgumentException("null key or set");
            nodes.add(k);
            List<String> l = new ArrayList<>();
            for (String d : v) {
                if (d == null) throw new IllegalArgumentException("null node");
                nodes.add(d);
                l.add(d);
            }
            deps.put(k, l);
        }
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (String n : nodes) {
            List<String> l = deps.getOrDefault(n, Collections.emptyList());
            remaining.put(n, l.size());
            for (String d : l) dependents.computeIfAbsent(d, x -> new ArrayList<>()).add(n);
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (String n : nodes) if (remaining.get(n) == 0) ready.add(n);
        List<String> out = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String n = ready.poll();
            out.add(n);
            for (String m : dependents.getOrDefault(n, Collections.emptyList())) {
                int r = remaining.merge(m, -1, Integer::sum);
                if (r == 0) ready.add(m);
            }
        }
        if (out.size() == nodes.size()) return out;
        Set<String> done = new HashSet<>(out);
        String start = new TreeSet<>(nodes).stream().filter(x -> !done.contains(x)).findFirst().orElseThrow();
        Map<String, Integer> idx = new HashMap<>();
        List<String> path = new ArrayList<>();
        String cur = start;
        while (!idx.containsKey(cur)) {
            idx.put(cur, path.size());
            path.add(cur);
            String next = null;
            for (String d : deps.get(cur)) {
                if (!done.contains(d)) {
                    next = d;
                    break;
                }
            }
            cur = next;
        }
        List<String> cycle = new ArrayList<>(path.subList(idx.get(cur), path.size()));
        cycle.add(cur);
        throw new CycleException(cycle);
    }
}
