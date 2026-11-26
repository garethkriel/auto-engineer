package ae.cfg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Natural loops, loop depth and reducibility. */
public final class Loops {
    /** One loop per header: its body (header included, sorted) and the tails of its back edges (ascending). */
    public record Loop(int header, Set<Integer> blocks, List<Integer> tails) {
        public Loop {
            blocks = Set.copyOf(blocks);
            tails = List.copyOf(tails);
        }
    }

    private final List<Loop> loops = new ArrayList<>();
    private final int[] depth;
    private final boolean reducible;

    public Loops(Cfg cfg, Dominators dom) {
        int n = cfg.size();
        TreeMap<Integer, List<Integer>> tailsByHeader = new TreeMap<>();
        for (int t = 0; t < n; t++) {
            for (int h : cfg.succ(t)) {
                if (dom.dominates(h, t)) {
                    tailsByHeader.computeIfAbsent(h, k -> new ArrayList<>()).add(t);
                }
            }
        }
        depth = new int[n];
        for (var e : tailsByHeader.entrySet()) {
            int header = e.getKey();
            Set<Integer> body = new TreeSet<>();
            body.add(header);
            ArrayDeque<Integer> work = new ArrayDeque<>();
            for (int t : e.getValue()) {
                if (body.add(t)) {
                    work.push(t);
                }
            }
            while (!work.isEmpty()) {
                for (int p : cfg.pred(work.pop())) {
                    if (cfg.reachable(p) && body.add(p)) {
                        work.push(p);
                    }
                }
            }
            for (int b : body) {
                depth[b]++;
            }
            loops.add(new Loop(header, body, e.getValue()));
        }
        reducible = acyclicWithoutBackEdges(cfg, dom);
    }

    /** Colour DFS over reachable blocks, skipping back edges; a cycle left over means an irreducible region. */
    private static boolean acyclicWithoutBackEdges(Cfg cfg, Dominators dom) {
        int n = cfg.size();
        int[] state = new int[n];                 // 0 new, 1 on stack, 2 done
        int[] next = new int[n];
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.push(0);
        state[0] = 1;
        while (!stack.isEmpty()) {
            int b = stack.peek();
            List<Integer> succ = cfg.succ(b);
            if (next[b] < succ.size()) {
                int s = succ.get(next[b]++);
                if (dom.dominates(s, b)) {
                    continue;                      // back edge
                }
                if (state[s] == 1) {
                    return false;
                }
                if (state[s] == 0) {
                    state[s] = 1;
                    stack.push(s);
                }
            } else {
                state[b] = 2;
                stack.pop();
            }
        }
        return true;
    }

    /** Loops ordered by header index. */
    public List<Loop> loops() {
        return List.copyOf(loops);
    }

    /** Number of loops containing block b. */
    public int depth(int b) {
        return depth[b];
    }

    public boolean reducible() {
        return reducible;
    }
}
