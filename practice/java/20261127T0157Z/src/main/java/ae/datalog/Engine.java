package ae.datalog;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Validates a {@link Program} and evaluates it bottom-up, stratum by stratum. */
public final class Engine {
    /** Evaluation strategy; both must give identical models. */
    public enum Mode { NAIVE, SEMI_NAIVE }

    private final List<Rule> rules;
    private final Map<String, Integer> arity = new TreeMap<>();
    private final Map<String, Integer> stratum = new TreeMap<>();
    private long work;
    private Model last;

    /** Validates arity, safety and stratifiability; throws {@link DatalogException} otherwise. */
    public Engine(Program program) {
        this.rules = program.rules();
        for (Rule r : rules) {
            declare(r.head());
            for (Literal l : r.body()) {
                if (l instanceof Literal.Pos p) declare(p.atom());
                if (l instanceof Literal.Neg n) declare(n.atom());
            }
        }
        for (Atom q : program.queries()) checkArity(q);
        for (Rule r : rules) checkSafety(r);
        computeStrata();
    }

    private void declare(Atom a) {
        Integer old = arity.putIfAbsent(a.pred(), a.arity());
        if (old != null && old != a.arity())
            throw new DatalogException("arity mismatch for " + a.pred() + ": " + old + " vs " + a.arity());
    }

    private void checkArity(Atom a) {
        Integer old = arity.get(a.pred());
        if (old != null && old != a.arity())
            throw new DatalogException("arity mismatch for " + a.pred() + ": " + old + " vs " + a.arity());
    }

    private static void vars(Term t, Set<String> out) {
        if (t instanceof Term.Var v) out.add(v.name());
    }

    private static void vars(Atom a, Set<String> out) {
        for (Term t : a.args()) vars(t, out);
    }

    private static void checkSafety(Rule r) {
        Set<String> bound = new HashSet<>();
        for (Literal l : r.body()) if (l instanceof Literal.Pos p) vars(p.atom(), bound);
        Set<String> need = new TreeSet<>();
        vars(r.head(), need);
        for (Literal l : r.body()) {
            switch (l) {
                case Literal.Neg n -> vars(n.atom(), need);
                case Literal.Eq e -> { vars(e.left(), need); vars(e.right(), need); }
                case Literal.Neq e -> { vars(e.left(), need); vars(e.right(), need); }
                case Literal.Pos p -> { }
            }
        }
        for (String v : need)
            if (!bound.contains(v)) throw new DatalogException("unsafe rule for " + r.head().pred() + ": variable " + v
                + " does not occur in a positive body atom");
    }

    private void computeStrata() {
        Set<String> preds = arity.keySet();
        Map<String, Set<String>> reach = new HashMap<>();
        for (String p : preds) reach.put(p, new HashSet<>());
        for (Rule r : rules)
            for (Literal l : r.body()) {
                if (l instanceof Literal.Pos p) reach.get(r.head().pred()).add(p.atom().pred());
                if (l instanceof Literal.Neg n) reach.get(r.head().pred()).add(n.atom().pred());
            }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String p : preds)
                for (String q : new ArrayList<>(reach.get(p)))
                    grew |= reach.get(p).addAll(reach.get(q));
        }
        Set<String> bad = new TreeSet<>();
        for (Rule r : rules)
            for (Literal l : r.body())
                if (l instanceof Literal.Neg n) {
                    String h = r.head().pred(), b = n.atom().pred();
                    if (h.equals(b) || reach.get(b).contains(h)) {
                        bad.add(h);
                        bad.add(b);
                        for (String x : preds) if (reach.get(h).contains(x) && reach.get(x).contains(h)) bad.add(x);
                    }
                }
        if (!bad.isEmpty()) throw new DatalogException("not stratifiable: negation inside a cycle through " + bad);
        for (String p : preds) stratum.put(p, 0);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Rule r : rules)
                for (Literal l : r.body()) {
                    int need = switch (l) {
                        case Literal.Pos p -> stratum.get(p.atom().pred());
                        case Literal.Neg n -> stratum.get(n.atom().pred()) + 1;
                        default -> 0;
                    };
                    if (stratum.get(r.head().pred()) < need) {
                        stratum.put(r.head().pred(), need);
                        changed = true;
                    }
                }
        }
    }

    /** Number of candidate tuples examined by the joins of the last {@link #evaluate}. */
    public long stats() {
        return work;
    }

    /** Computes the model. */
    public Model evaluate(Mode mode) {
        work = 0;
        Map<String, Set<List<String>>> full = new HashMap<>();
        List<Rule> proper = new ArrayList<>();
        for (Rule r : rules) {
            if (r.body().isEmpty()) {
                List<String> t = new ArrayList<>();
                for (Term x : r.head().args()) t.add(((Term.Const) x).text());
                full.computeIfAbsent(r.head().pred(), k -> new LinkedHashSet<>()).add(t);
            } else {
                proper.add(r);
            }
        }
        int top = stratum.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int s = 0; s <= top; s++) {
            List<Rule> here = new ArrayList<>();
            for (Rule r : proper) if (stratum.get(r.head().pred()) == s) here.add(r);
            if (here.isEmpty()) continue;
            if (mode == Mode.NAIVE) naive(here, full);
            else semiNaive(here, full, s);
        }
        last = new Model(full);
        return last;
    }

    private static boolean addAll(Map<String, Set<List<String>>> into, String pred, Set<List<String>> add) {
        return into.computeIfAbsent(pred, k -> new LinkedHashSet<>()).addAll(add);
    }

    private void naive(List<Rule> here, Map<String, Set<List<String>>> full) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Rule r : here) {
                Set<List<String>> out = new LinkedHashSet<>();
                derive(r, full, -1, null, out);
                changed |= addAll(full, r.head().pred(), out);
            }
        }
    }

    private void semiNaive(List<Rule> here, Map<String, Set<List<String>>> full, int s) {
        Set<String> mine = new HashSet<>();
        for (Rule r : here) mine.add(r.head().pred());
        // initial pass over everything known so far
        for (Rule r : here) {
            Set<List<String>> out = new LinkedHashSet<>();
            derive(r, full, -1, null, out);
            addAll(full, r.head().pred(), out);
        }
        Map<String, Set<List<String>>> delta = new HashMap<>();
        for (String p : mine) if (full.containsKey(p)) delta.put(p, new LinkedHashSet<>(full.get(p)));
        while (!delta.isEmpty()) {
            Map<String, Set<List<String>>> next = new HashMap<>();
            for (Rule r : here) {
                for (int i = 0; i < r.body().size(); i++) {
                    if (!(r.body().get(i) instanceof Literal.Pos p) || !mine.contains(p.atom().pred())) continue;
                    if (!delta.containsKey(p.atom().pred())) continue;
                    Set<List<String>> out = new LinkedHashSet<>();
                    derive(r, full, i, delta, out);
                    Set<List<String>> fresh = new LinkedHashSet<>(out);
                    fresh.removeAll(full.getOrDefault(r.head().pred(), Set.of()));
                    if (!fresh.isEmpty()) addAll(next, r.head().pred(), fresh);
                }
            }
            for (Map.Entry<String, Set<List<String>>> e : next.entrySet()) addAll(full, e.getKey(), e.getValue());
            delta = next;
        }
    }

    /** Joins the body; literal {@code deltaIdx} (original index) reads from {@code delta}, others from {@code rels}. */
    private void derive(Rule r, Map<String, Set<List<String>>> rels, int deltaIdx,
                        Map<String, Set<List<String>>> delta, Set<List<String>> out) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < r.body().size(); i++) if (r.body().get(i) instanceof Literal.Pos) order.add(i);
        for (int i = 0; i < r.body().size(); i++) if (!(r.body().get(i) instanceof Literal.Pos)) order.add(i);
        join(r, order, 0, new HashMap<>(), rels, deltaIdx, delta, out);
    }

    private void join(Rule r, List<Integer> order, int k, Map<String, String> env, Map<String, Set<List<String>>> rels,
                      int deltaIdx, Map<String, Set<List<String>>> delta, Set<List<String>> out) {
        if (k == order.size()) {
            List<String> t = new ArrayList<>();
            for (Term x : r.head().args()) t.add(value(x, env));
            out.add(t);
            return;
        }
        int idx = order.get(k);
        switch (r.body().get(idx)) {
            case Literal.Pos p -> {
                Map<String, Set<List<String>>> src = idx == deltaIdx ? delta : rels;
                for (List<String> tuple : new ArrayList<>(src.getOrDefault(p.atom().pred(), Set.of()))) {
                    work++;
                    List<String> bound = new ArrayList<>();
                    if (unify(p.atom().args(), tuple, env, bound))
                        join(r, order, k + 1, env, rels, deltaIdx, delta, out);
                    for (String v : bound) env.remove(v);
                }
            }
            case Literal.Neg n -> {
                work++;
                List<String> t = new ArrayList<>();
                for (Term x : n.atom().args()) t.add(value(x, env));
                if (!rels.getOrDefault(n.atom().pred(), Set.of()).contains(t))
                    join(r, order, k + 1, env, rels, deltaIdx, delta, out);
            }
            case Literal.Eq e -> {
                work++;
                if (value(e.left(), env).equals(value(e.right(), env)))
                    join(r, order, k + 1, env, rels, deltaIdx, delta, out);
            }
            case Literal.Neq e -> {
                work++;
                if (!value(e.left(), env).equals(value(e.right(), env)))
                    join(r, order, k + 1, env, rels, deltaIdx, delta, out);
            }
        }
    }

    private static String value(Term t, Map<String, String> env) {
        return switch (t) {
            case Term.Const c -> c.text();
            case Term.Var v -> env.get(v.name());
        };
    }

    private static boolean unify(List<Term> args, List<String> tuple, Map<String, String> env, List<String> bound) {
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case Term.Const c -> {
                    if (!c.text().equals(tuple.get(i))) return false;
                }
                case Term.Var v -> {
                    String cur = env.get(v.name());
                    if (cur == null) {
                        env.put(v.name(), tuple.get(i));
                        bound.add(v.name());
                    } else if (!cur.equals(tuple.get(i))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isInt(String s) {
        return s.charAt(0) == '-' || Character.isDigit(s.charAt(0));
    }

    private static int cmp(String a, String b) {
        boolean ia = isInt(a), ib = isInt(b);
        if (ia && ib) return new BigInteger(a).compareTo(new BigInteger(b));
        if (ia) return -1;
        if (ib) return 1;
        return a.compareTo(b);
    }

    /** Answers a query against the last model (evaluating semi-naively first if needed). */
    public List<Map<String, String>> query(Atom q) {
        checkArity(q);
        if (last == null) evaluate(Mode.SEMI_NAIVE);
        List<Map<String, String>> res = new ArrayList<>();
        for (List<String> tuple : last.getOrDefault(q.pred(), Set.of())) {
            Map<String, String> env = new LinkedHashMap<>();
            if (unify(q.args(), tuple, env, new ArrayList<>())) res.add(env);
        }
        Comparator<Map<String, String>> byTuple = (x, y) -> {
            for (String k : x.keySet()) {
                int c = cmp(x.get(k), y.get(k));
                if (c != 0) return c;
            }
            return 0;
        };
        Collections.sort(res, byTuple);
        return res;
    }
}
