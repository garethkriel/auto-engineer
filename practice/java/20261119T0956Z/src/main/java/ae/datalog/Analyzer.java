package ae.datalog;

import ae.datalog.Syntax.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Arity, safety and stratification checks. */
final class Analyzer {
    private Analyzer() {}

    /** Checks the program and returns the stratum of every predicate. */
    static Map<String, Integer> analyze(Program p) {
        Map<String, Integer> arity = new HashMap<>();
        for (Atom a : p.facts()) {
            checkArity(arity, a);
        }
        for (Rule r : p.rules()) {
            checkArity(arity, r.head());
            for (Item it : r.body()) {
                if (it instanceof Pos_ x) {
                    checkArity(arity, x.atom());
                } else if (it instanceof Neg n) {
                    checkArity(arity, n.atom());
                }
            }
        }
        for (Atom q : p.queries()) {
            checkArity(arity, q);
        }
        for (Rule r : p.rules()) {
            checkSafety(r);
        }
        return stratify(p, arity.keySet());
    }

    private static void checkArity(Map<String, Integer> arity, Atom a) {
        Integer prev = arity.putIfAbsent(a.pred(), a.args().size());
        if (prev != null && prev != a.args().size()) {
            throw new DatalogException(a.pos().line(), a.pos().column(),
                "arity mismatch: " + a.pred() + " used with " + a.args().size() + " arguments but " + prev
                    + " elsewhere");
        }
    }

    private static void checkSafety(Rule r) {
        Set<String> bound = new HashSet<>();
        for (Item it : r.body()) {
            if (it instanceof Pos_ x) {
                for (Term t : x.atom().args()) {
                    if (t instanceof Var v) {
                        bound.add(v.name());
                    }
                }
            }
        }
        for (Term t : r.head().args()) {
            need(r, bound, t);
        }
        for (Item it : r.body()) {
            if (it instanceof Neg n) {
                for (Term t : n.atom().args()) {
                    if (t instanceof Var v && v.anonymous()) {
                        continue;
                    }
                    need(r, bound, t);
                }
            } else if (it instanceof Neq q) {
                need(r, bound, q.left());
                need(r, bound, q.right());
            }
        }
    }

    private static void need(Rule r, Set<String> bound, Term t) {
        if (t instanceof Var v && !bound.contains(v.name())) {
            throw new DatalogException(r.pos().line(), r.pos().column(),
                "unsafe rule for " + r.head().pred() + ": variable " + v.name()
                    + " is not bound by a positive body atom");
        }
    }

    private static Map<String, Integer> stratify(Program p, Set<String> preds) {
        Map<String, Integer> s = new LinkedHashMap<>();
        for (String x : preds) {
            s.put(x, 0);
        }
        int limit = preds.size();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Rule r : p.rules()) {
                String h = r.head().pred();
                for (Item it : r.body()) {
                    int need;
                    if (it instanceof Pos_ x) {
                        need = s.get(x.atom().pred());
                    } else if (it instanceof Neg n) {
                        need = s.get(n.atom().pred()) + 1;
                    } else {
                        continue;
                    }
                    if (need > s.get(h)) {
                        if (need > limit) {
                            throw new DatalogException(r.pos().line(), r.pos().column(),
                                "program is not stratifiable: " + h + " depends on itself through negation");
                        }
                        s.put(h, need);
                        changed = true;
                    }
                }
            }
        }
        return s;
    }

    static List<Rule> rulesOf(Program p, Map<String, Integer> strata, int stratum) {
        return p.rules().stream().filter(r -> strata.get(r.head().pred()) == stratum).toList();
    }
}
