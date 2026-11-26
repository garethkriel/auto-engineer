package ae.cfg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Slow, definition-level oracles written independently of the production algorithms. */
final class Oracles {
    private Oracles() { }

    /** Blocks reachable from the entry without ever entering {@code removed} (-1: remove nothing). */
    static Set<Integer> reachAvoiding(Cfg cfg, int removed) {
        Set<Integer> seen = new HashSet<>();
        if (removed == 0) {
            return seen;
        }
        ArrayDeque<Integer> work = new ArrayDeque<>(List.of(0));
        seen.add(0);
        while (!work.isEmpty()) {
            for (int s : cfg.succ(work.pop())) {
                if (s != removed && seen.add(s)) {
                    work.push(s);
                }
            }
        }
        return seen;
    }

    /** a dominates b iff b is reachable and every path entry -> b passes through a. */
    static boolean dominates(Cfg cfg, int a, int b) {
        Set<Integer> all = reachAvoiding(cfg, -1);
        if (!all.contains(b) || !all.contains(a)) {
            return false;
        }
        return a == b || !reachAvoiding(cfg, a).contains(b);
    }

    /** The strict dominator of b that every other strict dominator of b dominates (-1 if none). */
    static int idom(Cfg cfg, int b) {
        List<Integer> strict = new ArrayList<>();
        for (int d = 0; d < cfg.size(); d++) {
            if (d != b && dominates(cfg, d, b)) {
                strict.add(d);
            }
        }
        for (int cand : strict) {
            boolean closest = true;
            for (int other : strict) {
                if (!dominates(cfg, other, cand)) {
                    closest = false;
                }
            }
            if (closest) {
                return cand;
            }
        }
        return -1;
    }

    /** DF(a) = { y : a dominates some predecessor of y, and a does not strictly dominate y }. Needs no entry special case: the definition already counts a back edge into the entry. */
    static Set<Integer> frontier(Cfg cfg, int a) {
        Set<Integer> out = new TreeSet<>();
        for (int y = 0; y < cfg.size(); y++) {
            boolean domPred = false;
            for (int p : cfg.pred(y)) {
                domPred |= dominates(cfg, a, p);
            }
            boolean strict = a != y && dominates(cfg, a, y);
            if (domPred && !strict && dominates(cfg, 0, y)) {
                out.add(y);
            }
        }
        return out;
    }

    /** Natural loop body of header h: h plus every block that can reach a tail without entering h. */
    static Set<Integer> loopBody(Cfg cfg, int h, List<Integer> tails) {
        Set<Integer> body = new TreeSet<>(List.of(h));
        for (int n = 0; n < cfg.size(); n++) {
            if (n == h || !cfg.reachable(n)) {
                continue;
            }
            ArrayDeque<Integer> work = new ArrayDeque<>(List.of(n));
            Set<Integer> seen = new HashSet<>(List.of(n));
            boolean hit = false;
            while (!work.isEmpty() && !hit) {
                int x = work.pop();
                hit = tails.contains(x);
                for (int s : cfg.succ(x)) {
                    if (s != h && seen.add(s)) {
                        work.push(s);
                    }
                }
            }
            if (hit) {
                body.add(n);
            }
        }
        return body;
    }

    /** T1/T2 reduction on the reachable subgraph: reducible iff it collapses to a single node. */
    static boolean reducible(Cfg cfg) {
        int n = cfg.size();
        List<Set<Integer>> succ = new ArrayList<>();
        for (int b = 0; b < n; b++) {
            succ.add(new TreeSet<>());
            if (cfg.reachable(b)) {
                for (int s : cfg.succ(b)) {
                    succ.get(b).add(s);
                }
            }
        }
        Set<Integer> alive = new TreeSet<>();
        for (int b = 0; b < n; b++) {
            if (cfg.reachable(b)) {
                alive.add(b);
            }
        }
        boolean changed = true;
        while (changed && alive.size() > 1) {
            changed = false;
            for (int b : alive) {
                if (succ.get(b).remove(b)) {      // T1: delete a self loop
                    changed = true;
                }
            }
            for (int b : new ArrayList<>(alive)) {
                if (b == 0) {
                    continue;
                }
                List<Integer> preds = new ArrayList<>();
                for (int p : alive) {
                    if (succ.get(p).contains(b)) {
                        preds.add(p);
                    }
                }
                if (preds.size() == 1) {          // T2: merge b into its unique predecessor
                    int p = preds.get(0);
                    succ.get(p).remove(b);
                    succ.get(p).addAll(succ.get(b));
                    alive.remove(b);
                    changed = true;
                }
            }
        }
        return alive.size() == 1;
    }

    /** Is variable v read before being redefined, scanning forward from instruction {@code from} of block b? */
    static boolean readsBeforeDef(Function f, Cfg cfg, String v, int b, int from, Set<Integer> visitedBlocks) {
        List<Instr> ins = f.blocks().get(b).instrs();
        for (int j = from; j < ins.size(); j++) {
            if (ins.get(j).uses().contains(v)) {
                return true;
            }
            if (ins.get(j).def().map(v::equals).orElse(false)) {
                return false;
            }
        }
        for (int s : cfg.succ(b)) {
            if (visitedBlocks.add(s) && readsBeforeDef(f, cfg, v, s, 0, visitedBlocks)) {
                return true;
            }
        }
        return false;
    }

    static Set<String> allVars(Function f) {
        Set<String> vars = new TreeSet<>(f.params());
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                i.def().ifPresent(vars::add);
                vars.addAll(i.uses());
            }
        }
        return vars;
    }

    static Set<String> liveIn(Function f, Cfg cfg, int b) {
        Set<String> out = new TreeSet<>();
        for (String v : allVars(f)) {
            if (readsBeforeDef(f, cfg, v, b, 0, new HashSet<>())) {
                out.add(v);
            }
        }
        return out;
    }

    static Set<String> liveAfter(Function f, Cfg cfg, int b, int idx) {
        Set<String> out = new TreeSet<>();
        for (String v : allVars(f)) {
            if (readsBeforeDef(f, cfg, v, b, idx + 1, new HashSet<>())) {
                out.add(v);
            }
        }
        return out;
    }
}
