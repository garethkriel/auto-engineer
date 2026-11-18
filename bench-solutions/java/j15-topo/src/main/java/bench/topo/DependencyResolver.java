package bench.topo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Lexicographically smallest topological order. */
public final class DependencyResolver {
    private DependencyResolver() {
    }

    public static List<String> order(Map<String, Set<String>> dependsOn) {
        if (dependsOn == null) {
            throw new IllegalArgumentException("null map");
        }
        Map<String, Integer> index = new HashMap<>();
        List<String> names = new ArrayList<>();
        List<List<Integer>> deps = new ArrayList<>(); // distinct deps per node
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("null key or dependency set");
            }
            id(e.getKey(), index, names, deps);
            for (String d : e.getValue()) {
                if (d == null) {
                    throw new IllegalArgumentException("null node name");
                }
                id(d, index, names, deps);
            }
        }
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            int x = index.get(e.getKey());
            List<Integer> list = deps.get(x);
            for (String d : e.getValue()) {
                list.add(index.get(d));
            }
        }
        int n = names.size();
        List<List<Integer>> dependents = new ArrayList<>();
        int[] indeg = new int[n];
        for (int i = 0; i < n; i++) {
            dependents.add(new ArrayList<>());
        }
        for (int x = 0; x < n; x++) {
            // sets guarantee distinctness per source set
            for (int d : deps.get(x)) {
                dependents.get(d).add(x);
                indeg[x]++;
            }
        }
        PriorityQueue<String> pq = new PriorityQueue<>();
        for (int i = 0; i < n; i++) {
            if (indeg[i] == 0) {
                pq.add(names.get(i));
            }
        }
        List<String> out = new ArrayList<>(n);
        boolean[] done = new boolean[n];
        while (!pq.isEmpty()) {
            String name = pq.poll();
            int x = index.get(name);
            done[x] = true;
            out.add(name);
            for (int y : dependents.get(x)) {
                if (--indeg[y] == 0) {
                    pq.add(names.get(y));
                }
            }
        }
        if (out.size() == n) {
            return out;
        }
        throw new CycleException(findCycle(n, done, deps, names));
    }

    private static int id(String s, Map<String, Integer> index, List<String> names,
                          List<List<Integer>> deps) {
        Integer i = index.get(s);
        if (i == null) {
            i = names.size();
            index.put(s, i);
            names.add(s);
            deps.add(new ArrayList<>());
        }
        return i;
    }

    private static List<String> findCycle(int n, boolean[] done, List<List<Integer>> deps,
                                          List<String> names) {
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (!done[i]) {
                start = i;
                break;
            }
        }
        int[] pathPos = new int[n];
        java.util.Arrays.fill(pathPos, -1);
        List<Integer> path = new ArrayList<>();
        int cur = start;
        while (pathPos[cur] < 0) {
            pathPos[cur] = path.size();
            path.add(cur);
            int next = -1;
            for (int d : deps.get(cur)) {
                if (!done[d]) {
                    next = d;
                    break;
                }
            }
            cur = next; // every unprocessed node has an unprocessed dependency
        }
        List<String> cycle = new ArrayList<>();
        for (int i = pathPos[cur]; i < path.size(); i++) {
            cycle.add(names.get(path.get(i)));
        }
        cycle.add(names.get(cur));
        return cycle;
    }
}
