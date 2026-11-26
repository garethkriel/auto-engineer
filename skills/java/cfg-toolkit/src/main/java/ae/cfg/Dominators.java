package ae.cfg;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Dominator tree (Cooper-Harvey-Kennedy iteration over reverse post-order) and dominance frontiers. */
public final class Dominators {
    private final Cfg cfg;
    private final int[] idom;
    private final List<Set<Integer>> frontier = new ArrayList<>();
    private final List<List<Integer>> children = new ArrayList<>();

    public Dominators(Cfg cfg) {
        this.cfg = cfg;
        int n = cfg.size();
        idom = new int[n];
        java.util.Arrays.fill(idom, -1);
        List<Integer> order = cfg.rpo();
        int[] number = new int[n];
        java.util.Arrays.fill(number, -1);
        for (int i = 0; i < order.size(); i++) {
            number[order.get(i)] = i;
        }
        idom[0] = 0;                                   // temporarily its own idom; reset below
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int b : order) {
                if (b == 0) {
                    continue;
                }
                int newIdom = -1;
                for (int p : cfg.pred(b)) {
                    if (idom[p] == -1) {
                        continue;                      // unreachable or not processed yet
                    }
                    newIdom = newIdom == -1 ? p : intersect(p, newIdom, number);
                }
                if (idom[b] != newIdom) {
                    idom[b] = newIdom;
                    changed = true;
                }
            }
        }
        idom[0] = -1;
        for (int b = 0; b < n; b++) {
            frontier.add(new TreeSet<>());
            children.add(new ArrayList<>());
        }
        for (int b = 0; b < n; b++) {
            if (idom[b] >= 0) {
                children.get(idom[b]).add(b);
            }
        }
        for (int b = 0; b < n; b++) {
            if (!cfg.reachable(b)) {
                continue;
            }
            List<Integer> preds = new ArrayList<>();
            for (int p : cfg.pred(b)) {
                if (cfg.reachable(p)) {
                    preds.add(p);
                }
            }
            if (preds.size() + (b == 0 ? 1 : 0) < 2) {
                continue;
            }
            for (int p : preds) {
                int runner = p;
                while (runner != idom[b]) {
                    frontier.get(runner).add(b);
                    runner = idom[runner];
                }
            }
        }
    }

    private int intersect(int a, int b, int[] number) {
        while (a != b) {
            while (number[a] > number[b]) {
                a = a == 0 ? 0 : idom[a];
            }
            while (number[b] > number[a]) {
                b = b == 0 ? 0 : idom[b];
            }
        }
        return a;
    }

    /** Immediate dominator, or -1 for the entry and for unreachable blocks. */
    public int idom(int b) {
        return idom[b];
    }

    /** Reflexive dominance; false if either block is unreachable. */
    public boolean dominates(int a, int b) {
        if (!cfg.reachable(a) || !cfg.reachable(b)) {
            return false;
        }
        for (int x = b; x != -1; x = idom[x]) {
            if (x == a) {
                return true;
            }
        }
        return false;
    }

    public Set<Integer> frontier(int b) {
        return new TreeSet<>(frontier.get(b));
    }

    /** Children in the dominator tree, ascending. */
    public List<Integer> children(int b) {
        return List.copyOf(children.get(b));
    }
}
