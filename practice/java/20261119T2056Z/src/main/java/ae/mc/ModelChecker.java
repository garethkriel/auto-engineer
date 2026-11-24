package ae.mc;

import java.util.*;
import java.util.function.Predicate;

/** Explicit-state, breadth-first model checker for safety and "eventually" (liveness) properties. */
public final class ModelChecker {
    private ModelChecker() {}

    /**
     * Checks invariants on every reachable state and, unless {@code isTerminal} accepts it, flags states without
     * moves as deadlocks. Counterexamples are shortest; at equal depth the first discovered wins.
     *
     * @param invariants checked in iteration order (use a LinkedHashMap for a defined order)
     * @param maxStates  bound on distinct states; exceeding it before a verdict gives {@code Incomplete}
     */
    public static <S> Verdict<S> checkSafety(
            Model<S> model, Map<String, Predicate<S>> invariants, Predicate<S> isTerminal, int maxStates) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(invariants);
        Objects.requireNonNull(isTerminal);
        if (maxStates < 1) throw new IllegalArgumentException("maxStates must be >= 1");
        Map<S, Step<S>> how = new HashMap<>(); // state -> step that first reached it
        Map<S, S> parent = new HashMap<>();
        Deque<S> queue = new ArrayDeque<>();
        int transitions = 0;
        for (S s : model.initial()) {
            if (how.containsKey(s)) continue;
            if (how.size() >= maxStates) return new Verdict.Incomplete<>(how.size());
            how.put(s, new Step<>("init", s));
            parent.put(s, null);
            Verdict<S> bad = verdictAt(model, invariants, isTerminal, s, how, parent);
            if (bad != null) return bad;
            queue.add(s);
        }
        while (!queue.isEmpty()) {
            S s = queue.poll();
            for (Move<S> mv : model.moves(s)) {
                transitions++;
                S t = mv.target();
                if (how.containsKey(t)) continue;
                if (how.size() >= maxStates) return new Verdict.Incomplete<>(how.size());
                how.put(t, new Step<>(mv.label(), t));
                parent.put(t, s);
                Verdict<S> bad = verdictAt(model, invariants, isTerminal, t, how, parent);
                if (bad != null) return bad;
                queue.add(t);
            }
        }
        return new Verdict.Holds<>(how.size(), transitions);
    }

    /**
     * Judges a state at the moment it is discovered, so the first failure found is also the shallowest one
     * (checking deadlocks only when a state is dequeued could miss a shallower one behind a deeper violation).
     * Relies on {@code moves} being pure.
     */
    private static <S> Verdict<S> verdictAt(Model<S> model, Map<String, Predicate<S>> invariants,
            Predicate<S> isTerminal, S s, Map<S, Step<S>> how, Map<S, S> parent) {
        String broken = firstBroken(invariants, s);
        if (broken != null) return new Verdict.InvariantViolated<>(broken, traceTo(s, how, parent));
        if (model.moves(s).isEmpty() && !isTerminal.test(s)) return new Verdict.Deadlock<>(traceTo(s, how, parent));
        return null;
    }

    private static <S> String firstBroken(Map<String, Predicate<S>> invariants, S s) {
        for (Map.Entry<String, Predicate<S>> e : invariants.entrySet()) {
            if (!e.getValue().test(s)) return e.getKey();
        }
        return null;
    }

    private static <S> List<Step<S>> traceTo(S s, Map<S, Step<S>> how, Map<S, S> parent) {
        LinkedList<Step<S>> out = new LinkedList<>();
        for (S cur = s; cur != null; cur = parent.get(cur)) out.addFirst(how.get(cur));
        return new ArrayList<>(out);
    }

    /**
     * Checks that on every infinite execution {@code goal} eventually holds (no fairness assumed; terminal states
     * stutter forever). A violation is a reachable path of non-goal states ending in a cycle or stuck state.
     */
    public static <S> Verdict<S> checkEventually(Model<S> model, String name, Predicate<S> goal, int maxStates) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(goal);
        if (maxStates < 1) throw new IllegalArgumentException("maxStates must be >= 1");
        // 1. Build the explicit reachable graph.
        List<S> states = new ArrayList<>();
        Map<S, Integer> index = new HashMap<>();
        List<List<Integer>> succ = new ArrayList<>();
        List<List<String>> labels = new ArrayList<>();
        Deque<Integer> queue = new ArrayDeque<>();
        List<Integer> inits = new ArrayList<>();
        int transitions = 0;
        for (S s : model.initial()) {
            if (index.containsKey(s)) continue;
            if (states.size() >= maxStates) return new Verdict.Incomplete<>(states.size());
            int id = add(s, states, index, succ, labels);
            inits.add(id);
            queue.add(id);
        }
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (Move<S> mv : model.moves(states.get(u))) {
                transitions++;
                Integer v = index.get(mv.target());
                if (v == null) {
                    if (states.size() >= maxStates) return new Verdict.Incomplete<>(states.size());
                    v = add(mv.target(), states, index, succ, labels);
                    queue.add(v);
                }
                succ.get(u).add(v);
                labels.get(u).add(mv.label());
            }
        }
        int n = states.size();
        boolean[] bad = new boolean[n];
        for (int i = 0; i < n; i++) bad[i] = !goal.test(states.get(i));

        // 2. Tarjan SCCs over the bad-only subgraph, iteratively.
        int[] comp = new int[n];
        int[] compSize = new int[n];
        tarjan(n, succ, bad, comp, compSize);
        boolean[] witness = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!bad[i]) continue;
            boolean selfLoop = succ.get(i).contains(i);
            witness[i] = succ.get(i).isEmpty() || compSize[comp[i]] > 1 || selfLoop;
        }

        // 3. BFS from bad initial states through bad states to the nearest witness.
        int[] prev = new int[n];
        int[] prevEdge = new int[n];
        Arrays.fill(prev, -2);
        Deque<Integer> bfs = new ArrayDeque<>();
        for (int i : inits) {
            if (bad[i]) {
                prev[i] = -1;
                bfs.add(i);
            }
        }
        int hit = -1;
        while (!bfs.isEmpty()) {
            int u = bfs.poll();
            if (witness[u]) {
                hit = u;
                break;
            }
            for (int k = 0; k < succ.get(u).size(); k++) {
                int v = succ.get(u).get(k);
                if (bad[v] && prev[v] == -2) {
                    prev[v] = u;
                    prevEdge[v] = k;
                    bfs.add(v);
                }
            }
        }
        if (hit < 0) return new Verdict.Holds<>(n, transitions);
        LinkedList<Step<S>> prefix = new LinkedList<>();
        for (int cur = hit; cur >= 0; cur = prev[cur]) {
            String label = prev[cur] == -1 ? "init" : labels.get(prev[cur]).get(prevEdge[cur]);
            prefix.addFirst(new Step<>(label, states.get(cur)));
        }
        List<Step<S>> cycle = succ.get(hit).isEmpty() ? List.of() : cycleThrough(hit, succ, labels, states, comp);
        return new Verdict.LivenessViolated<>(name, new ArrayList<>(prefix), cycle);
    }

    private static <S> int add(
            S s, List<S> states, Map<S, Integer> index, List<List<Integer>> succ, List<List<String>> labels) {
        int id = states.size();
        states.add(s);
        index.put(s, id);
        succ.add(new ArrayList<>());
        labels.add(new ArrayList<>());
        return id;
    }

    /** Shortest cycle from {@code start} back to itself, staying inside its SCC. */
    private static <S> List<Step<S>> cycleThrough(
            int start, List<List<Integer>> succ, List<List<String>> labels, List<S> states, int[] comp) {
        int n = states.size();
        int[] prev = new int[n];
        int[] prevEdge = new int[n];
        Arrays.fill(prev, -2);
        Deque<Integer> q = new ArrayDeque<>();
        q.add(start);
        int last = -1;
        int lastEdge = -1;
        search:
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int k = 0; k < succ.get(u).size(); k++) {
                int v = succ.get(u).get(k);
                if (comp[v] != comp[start]) continue;
                if (v == start) {
                    last = u;
                    lastEdge = k;
                    break search;
                }
                if (prev[v] == -2) {
                    prev[v] = u;
                    prevEdge[v] = k;
                    q.add(v);
                }
            }
        }
        LinkedList<Step<S>> out = new LinkedList<>();
        out.addFirst(new Step<>(labels.get(last).get(lastEdge), states.get(start)));
        for (int cur = last; cur != start; cur = prev[cur]) {
            out.addFirst(new Step<>(labels.get(prev[cur]).get(prevEdge[cur]), states.get(cur)));
        }
        return new ArrayList<>(out);
    }

    /** Iterative Tarjan restricted to nodes with {@code active[i]}; fills comp ids and component sizes. */
    private static void tarjan(int n, List<List<Integer>> succ, boolean[] active, int[] comp, int[] compSize) {
        int[] idx = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(idx, -1);
        Arrays.fill(comp, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        int counter = 0;
        int comps = 0;
        for (int root = 0; root < n; root++) {
            if (!active[root] || idx[root] != -1) continue;
            Deque<int[]> call = new ArrayDeque<>(); // {node, next edge index}
            call.push(new int[] {root, 0});
            idx[root] = low[root] = counter++;
            stack.push(root);
            onStack[root] = true;
            while (!call.isEmpty()) {
                int[] f = call.peek();
                int u = f[0];
                if (f[1] < succ.get(u).size()) {
                    int v = succ.get(u).get(f[1]++);
                    if (!active[v]) continue;
                    if (idx[v] == -1) {
                        idx[v] = low[v] = counter++;
                        stack.push(v);
                        onStack[v] = true;
                        call.push(new int[] {v, 0});
                    } else if (onStack[v]) {
                        low[u] = Math.min(low[u], idx[v]);
                    }
                } else {
                    call.pop();
                    if (!call.isEmpty()) {
                        int p = call.peek()[0];
                        low[p] = Math.min(low[p], low[u]);
                    }
                    if (low[u] == idx[u]) {
                        int size = 0;
                        int w;
                        do {
                            w = stack.pop();
                            onStack[w] = false;
                            comp[w] = comps;
                            size++;
                        } while (w != u);
                        compSize[comps++] = size;
                    }
                }
            }
        }
    }
}
