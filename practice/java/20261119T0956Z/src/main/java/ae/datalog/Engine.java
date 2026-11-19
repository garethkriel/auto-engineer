package ae.datalog;

import ae.datalog.Syntax.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bottom-up evaluation, stratum by stratum. */
final class Engine {
    private final Program program;
    private final Map<String, Integer> strata;
    private final Mode mode;
    private final Map<String, Set<List<String>>> db = new HashMap<>();
    private long work;

    Engine(Program program, Map<String, Integer> strata, Mode mode) {
        this.program = program;
        this.strata = strata;
        this.mode = mode;
    }

    Result run() {
        for (String p : strata.keySet()) {
            db.put(p, new HashSet<>());
        }
        for (Atom f : program.facts()) {
            db.get(f.pred()).add(f.args().stream().map(t -> ((Const) t).value()).toList());
        }
        int top = strata.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int s = 0; s <= top; s++) {
            evaluateStratum(s);
        }
        List<List<Map<String, String>>> answers = new ArrayList<>();
        for (Atom q : program.queries()) {
            answers.add(answer(q));
        }
        return new Result(db, answers, top + 1, work);
    }

    private void evaluateStratum(int s) {
        List<Rule> rules = Analyzer.rulesOf(program, strata, s);
        if (rules.isEmpty()) {
            return;
        }
        Map<String, Set<List<String>>> delta = new HashMap<>();
        boolean first = true;
        while (true) {
            Map<String, Set<List<String>>> fresh = new HashMap<>();
            for (Rule r : rules) {
                List<List<String>> derived = new ArrayList<>();
                List<Atom> pos = positives(r);
                if (mode == Mode.NAIVE || first) {
                    // First round (or naive): everything visible is "old", so one plain join suffices.
                    join(r, pos, -1, null, derived);
                } else {
                    for (int i = 0; i < pos.size(); i++) {
                        Atom a = pos.get(i);
                        if (strata.get(a.pred()) == s && !delta.getOrDefault(a.pred(), Set.of()).isEmpty()) {
                            join(r, pos, i, delta, derived);
                        }
                    }
                }
                Set<List<String>> rel = db.get(r.head().pred());
                for (List<String> t : derived) {
                    if (!rel.contains(t)) {
                        fresh.computeIfAbsent(r.head().pred(), k -> new HashSet<>()).add(t);
                    }
                }
            }
            first = false;
            if (fresh.isEmpty()) {
                return;
            }
            fresh.forEach((p, ts) -> db.get(p).addAll(ts));
            delta = fresh;
        }
    }

    private static List<Atom> positives(Rule r) {
        List<Atom> out = new ArrayList<>();
        for (Item it : r.body()) {
            if (it instanceof Pos_ x) {
                out.add(x.atom());
            }
        }
        return out;
    }

    /** Joins the positive atoms; atom {@code deltaIdx} reads from {@code delta}, the rest from the full db. */
    private void join(Rule r, List<Atom> pos, int deltaIdx, Map<String, Set<List<String>>> delta,
                      List<List<String>> out) {
        joinFrom(r, pos, 0, deltaIdx, delta, new LinkedHashMap<>(), out);
    }

    private void joinFrom(Rule r, List<Atom> pos, int i, int deltaIdx, Map<String, Set<List<String>>> delta,
                          Map<String, String> env, List<List<String>> out) {
        if (i == pos.size()) {
            if (filtersHold(r, env)) {
                work++;
                out.add(r.head().args().stream().map(t -> value(t, env)).toList());
            }
            return;
        }
        Atom a = pos.get(i);
        Set<List<String>> source = i == deltaIdx ? delta.getOrDefault(a.pred(), Set.of()) : db.get(a.pred());
        for (List<String> tuple : source) {
            List<String> added = new ArrayList<>(2);
            if (unify(a, tuple, env, added)) {
                joinFrom(r, pos, i + 1, deltaIdx, delta, env, out);
            }
            added.forEach(env::remove);
        }
    }

    /** Extends {@code env} so that {@code a} matches {@code tuple}; records new variables in {@code added}. */
    private static boolean unify(Atom a, List<String> tuple, Map<String, String> env, List<String> added) {
        for (int k = 0; k < tuple.size(); k++) {
            Term t = a.args().get(k);
            if (t instanceof Const c) {
                if (!c.value().equals(tuple.get(k))) {
                    return false;
                }
            } else {
                String name = ((Var) t).name();
                String bound = env.get(name);
                if (bound == null) {
                    env.put(name, tuple.get(k));
                    added.add(name);
                } else if (!bound.equals(tuple.get(k))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean filtersHold(Rule r, Map<String, String> env) {
        for (Item it : r.body()) {
            if (it instanceof Neg n) {
                if (exists(n.atom(), env)) {
                    return false;
                }
            } else if (it instanceof Neq q) {
                if (value(q.left(), env).equals(value(q.right(), env))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True when some tuple matches {@code a}; unbound (anonymous) variables match anything. */
    private boolean exists(Atom a, Map<String, String> env) {
        for (List<String> tuple : db.get(a.pred())) {
            List<String> added = new ArrayList<>(2);
            boolean ok = unify(a, tuple, env, added);
            added.forEach(env::remove);
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private static String value(Term t, Map<String, String> env) {
        return t instanceof Const c ? c.value() : env.get(((Var) t).name());
    }

    private List<Map<String, String>> answer(Atom q) {
        List<String> vars = new ArrayList<>();
        for (Term t : q.args()) {
            if (t instanceof Var v && !v.anonymous() && !vars.contains(v.name())) {
                vars.add(v.name());
            }
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (List<String> tuple : db.get(q.pred())) {
            Map<String, String> env = new HashMap<>();
            if (unify(q, tuple, env, new ArrayList<>())) {
                Map<String, String> row = new HashMap<>();
                for (String v : vars) {
                    row.put(v, env.get(v));
                }
                out.add(row);
            }
        }
        List<List<String>> keyed = new ArrayList<>();
        Map<List<String>, Map<String, String>> byKey = new HashMap<>();
        for (Map<String, String> row : out) {
            List<String> key = vars.stream().map(row::get).toList();
            if (byKey.putIfAbsent(key, row) == null) {
                keyed.add(key);
            }
        }
        keyed.sort(Result.TUPLE_ORDER);
        return keyed.stream().map(byKey::get).toList();
    }
}
