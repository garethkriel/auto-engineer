package ae.prolog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * A Prolog-subset engine: SLD resolution with cut, run by an iterative machine
 * (continuation list + choicepoint stack + trail) so deep recursion never grows the Java stack.
 * Not thread-safe.
 */
public final class Engine {
    private static final String PRELUDE = String.join("\n",
        "append([],L,L).",
        "append([H|T],L,[H|R]) :- append(T,L,R).",
        "member(X,[X|_]).",
        "member(X,[_|T]) :- member(X,T).",
        "reverse(L,R) :- '$rev'(L,[],R).",
        "'$rev'([],A,A).",
        "'$rev'([H|T],A,R) :- '$rev'(T,[H|A],R).",
        "length(L,N) :- integer(N), !, N >= 0, '$mklist'(N,L).",
        "length(L,N) :- '$len'(L,0,N).",
        "'$len'([],N,N).",
        "'$len'([_|T],A,N) :- A1 is A+1, '$len'(T,A1,N).",
        "'$mklist'(0,[]) :- !.",
        "'$mklist'(N,[_|T]) :- M is N-1, '$mklist'(M,T).",
        "select(X,[X|T],T).",
        "select(X,[H|T],[H|R]) :- select(X,T,R).",
        "permutation([],[]).",
        "permutation(L,[H|T]) :- select(H,L,R), permutation(R,T).",
        "between(L,H,X) :- integer(X), !, X >= L, X =< H.",
        "between(L,H,L) :- L =< H.",
        "between(L,H,X) :- L < H, L1 is L+1, between(L1,H,X).",
        "sum_list([],0).",
        "sum_list([H|T],S) :- sum_list(T,S0), S is S0+H.",
        "last([X],X) :- !.",
        "last([_|T],X) :- last(T,X).",
        "once(G) :- call(G), !.",
        "ignore(G) :- ( call(G) -> true ; true ).",
        "");

    private final Map<String, List<Clause>> db = new HashMap<>();
    private final Set<String> library = new HashSet<>();
    private final boolean occursCheck;
    private final long maxSteps;

    public Engine() {
        this(false, 100_000_000L);
    }

    /**
     * @param occursCheck when true, binding a variable to a term containing it fails
     * @param maxSteps    goals one query may execute before a RESOURCE error
     */
    public Engine(boolean occursCheck, long maxSteps) {
        this.occursCheck = occursCheck;
        this.maxSteps = maxSteps;
        for (Clause c : Parser.parseProgram(PRELUDE)) add(c, false);
        library.addAll(db.keySet());
    }

    // ------------------------------------------------------------------ database

    private static String key(Term head) {
        head = Terms.deref(head);
        if (head instanceof Atom a) return a.name() + "/0";
        if (head instanceof Struct s) return s.name() + "/" + s.arity();
        if (head instanceof Var) throw new PrologError(PrologError.Kind.INSTANTIATION, "clause head is unbound");
        throw new PrologError(PrologError.Kind.TYPE, "callable expected as clause head");
    }

    private static final Set<String> CONTROL = Set.of(",/2", ";/2", "->/2", "!/0", "true/0", "fail/0", "false/0",
        "\\+/1", "not/1", "call/1", "call/2", "call/3", "call/4", "=/2", "\\=/2", "==/2", "\\==/2", "is/2", "=:=/2",
        "=\\=/2", "</2", ">/2", "=</2", ">=/2", "var/1", "nonvar/1", "atom/1", "integer/1", "atomic/1",
        "compound/1", "findall/3", "assertz/1", "asserta/1", "retract/1", "sort/2", "msort/2", "compare/3");

    private void add(Clause raw, boolean atFront) {
        String k = key(raw.head());
        if (CONTROL.contains(k) || library.contains(k)) {
            throw new PrologError(PrologError.Kind.PERMISSION, "cannot modify built-in or library predicate " + k);
        }
        Map<Var, Var> m = new IdentityHashMap<>();
        Clause c = new Clause(Terms.copy(raw.head(), m), Terms.copy(raw.body(), m));
        List<Clause> old = db.getOrDefault(k, List.of());
        List<Clause> fresh = new ArrayList<>(old.size() + 1);
        if (atFront) fresh.add(c);
        fresh.addAll(old);
        if (!atFront) fresh.add(c);
        db.put(k, List.copyOf(fresh)); // copy-on-write: running queries keep their snapshot (logical update view)
    }

    /** Adds the clauses in {@code program} (facts and rules; directives are not supported). */
    public void consult(String program) {
        for (Clause c : Parser.parseProgram(program)) add(c, false);
    }

    // ------------------------------------------------------------------ querying

    /** Lazily enumerates the answers of a query. Errors surface from {@code hasNext()}/{@code next()}. */
    public Iterator<Solution> query(String text) {
        Parser.Query q = Parser.parseQuery(text);
        Trail trail = new Trail();
        Run run = new Run(q.goal(), trail, 0);
        return new Iterator<>() {
            private Solution pending;
            private boolean finished;

            @Override
            public boolean hasNext() {
                if (pending == null && !finished) {
                    if (run.next()) {
                        Map<Var, Var> m = new IdentityHashMap<>();
                        Map<String, Term> b = new LinkedHashMap<>();
                        for (var en : q.variables().entrySet()) {
                            if (!en.getKey().startsWith("_")) b.put(en.getKey(), Terms.copy(en.getValue(), m));
                        }
                        pending = new Solution(b, run.cps.size());
                    } else {
                        finished = true;
                    }
                }
                return pending != null;
            }

            @Override
            public Solution next() {
                if (!hasNext()) throw new NoSuchElementException();
                Solution s = pending;
                pending = null;
                return s;
            }
        };
    }

    public List<Solution> all(String text) {
        return all(text, Integer.MAX_VALUE);
    }

    public List<Solution> all(String text, int limit) {
        List<Solution> out = new ArrayList<>();
        Iterator<Solution> it = query(text);
        while (out.size() < limit && it.hasNext()) out.add(it.next());
        return out;
    }

    public Optional<Solution> first(String text) {
        Iterator<Solution> it = query(text);
        return it.hasNext() ? Optional.of(it.next()) : Optional.empty();
    }

    public boolean holds(String text) {
        return first(text).isPresent();
    }

    // ------------------------------------------------------------------ machinery

    private static final class Trail {
        final ArrayList<Var> vars = new ArrayList<>();

        void bind(Var v, Term t) {
            v.ref = t;
            vars.add(v);
        }

        int mark() {
            return vars.size();
        }

        void undo(int mark) {
            for (int i = vars.size() - 1; i >= mark; i--) vars.get(i).ref = null;
            vars.subList(mark, vars.size()).clear();
        }
    }

    /** Continuation frame: run {@code goal}; a cut inside it prunes choicepoints back to {@code cutB}. */
    private record Cont(Term goal, int cutB, Cont next) {}

    private sealed interface Choice permits Alt, Clauses {
        int mark();
    }

    private record Alt(int mark, Cont cont) implements Choice {}

    private record Clauses(int mark, Term goal, List<Clause> list, int idx, Cont next) implements Choice {}

    private static final Term FAIL = new Atom("fail");

    private final class Run {
        private final Trail trail;
        private final int base;
        private final int depth;
        final ArrayList<Choice> cps = new ArrayList<>();
        private Cont goals;
        private boolean started;
        private boolean done;
        private long steps;

        Run(Term goal, Trail trail, int depth) {
            this.trail = trail;
            this.base = trail.mark();
            this.depth = depth;
            this.goals = new Cont(goal, 0, null);
        }

        /** Advances to the next answer; bindings stay in place until the following call. */
        boolean next() {
            if (done) return false;
            try {
                boolean ok = started ? (backtrack() && run()) : run();
                started = true;
                if (!ok) finish();
                return ok;
            } catch (RuntimeException | StackOverflowError e) {
                finish();
                if (e instanceof StackOverflowError) {
                    throw new PrologError(PrologError.Kind.RESOURCE, "term too deeply nested");
                }
                throw e;
            }
        }

        private void finish() {
            done = true;
            cps.clear();
            trail.undo(base);
        }

        private boolean run() {
            while (true) {
                if (goals == null) return true;
                if (++steps > maxSteps) throw new PrologError(PrologError.Kind.RESOURCE, "step limit exceeded");
                Cont c = goals;
                goals = c.next;
                if (!step(Terms.deref(c.goal), c) && !backtrack()) return false;
            }
        }

        private boolean backtrack() {
            while (!cps.isEmpty()) {
                Choice ch = cps.remove(cps.size() - 1);
                trail.undo(ch.mark());
                if (ch instanceof Alt a) {
                    goals = a.cont;
                    return true;
                }
                Clauses cc = (Clauses) ch;
                if (tryClauses(cc.goal, cc.list, cc.idx, cc.next)) return true;
            }
            return false;
        }

        private void cutTo(int n) {
            while (cps.size() > n) cps.remove(cps.size() - 1);
        }

        // ---------------------------------------------------------------- one goal

        private boolean step(Term g, Cont c) {
            if (g instanceof Var) throw new PrologError(PrologError.Kind.INSTANTIATION, "goal is unbound");
            if (g instanceof Int) throw new PrologError(PrologError.Kind.TYPE, "callable expected, found integer");
            String name = g instanceof Atom a ? a.name() : ((Struct) g).name();
            Term[] args = g instanceof Struct s ? s.args : new Term[0];
            switch (name + "/" + args.length) {
                case "true/0": return true;
                case "fail/0": case "false/0": return false;
                case "!/0": cutTo(c.cutB); return true;
                case "$cut/1": cutTo((int) ((Int) args[0]).value()); return true;
                case ",/2":
                    goals = new Cont(args[0], c.cutB, new Cont(args[1], c.cutB, goals));
                    return true;
                case ";/2": return disjunction(args, c);
                case "->/2": {
                    int b = cps.size();
                    goals = new Cont(args[0], b, new Cont(new Struct("$cut", new Int(b)), 0, new Cont(args[1], c.cutB, goals)));
                    return true;
                }
                case "\\+/1": case "not/1": {
                    int b = cps.size();
                    cps.add(new Alt(trail.mark(), goals));
                    goals = new Cont(args[0], b + 1, new Cont(new Struct("$cut", new Int(b)), 0, new Cont(FAIL, 0, null)));
                    return true;
                }
                case "call/1": case "call/2": case "call/3": case "call/4": {
                    goals = new Cont(addArgs(args), cps.size(), goals);
                    return true;
                }
                case "=/2": return unify(args[0], args[1]);
                case "\\=/2": {
                    int m = trail.mark();
                    boolean u = unify(args[0], args[1]);
                    trail.undo(m);
                    return !u;
                }
                case "==/2": return Terms.compare(args[0], args[1]) == 0;
                case "\\==/2": return Terms.compare(args[0], args[1]) != 0;
                case "is/2": return unify(args[0], new Int(eval(args[1])));
                case "=:=/2": return eval(args[0]) == eval(args[1]);
                case "=\\=/2": return eval(args[0]) != eval(args[1]);
                case "</2": return eval(args[0]) < eval(args[1]);
                case ">/2": return eval(args[0]) > eval(args[1]);
                case "=</2": return eval(args[0]) <= eval(args[1]);
                case ">=/2": return eval(args[0]) >= eval(args[1]);
                case "var/1": return Terms.deref(args[0]) instanceof Var;
                case "nonvar/1": return !(Terms.deref(args[0]) instanceof Var);
                case "atom/1": return Terms.deref(args[0]) instanceof Atom;
                case "integer/1": return Terms.deref(args[0]) instanceof Int;
                case "atomic/1": {
                    Term t = Terms.deref(args[0]);
                    return t instanceof Atom || t instanceof Int;
                }
                case "compound/1": return Terms.deref(args[0]) instanceof Struct;
                case "findall/3": return findall(args);
                case "assertz/1": add(toClause(args[0]), false); return true;
                case "asserta/1": add(toClause(args[0]), true); return true;
                case "retract/1": return retract(args[0]);
                case "sort/2": return unify(args[1], sorted(args[0], true));
                case "msort/2": return unify(args[1], sorted(args[0], false));
                case "compare/3": {
                    int r = Terms.compare(args[1], args[2]);
                    return unify(args[0], new Atom(r < 0 ? "<" : r == 0 ? "=" : ">"));
                }
                default: break;
            }
            String k = name + "/" + args.length;
            List<Clause> list = db.get(k);
            if (list == null) throw new PrologError(PrologError.Kind.EXISTENCE, "unknown procedure " + k);
            return tryClauses(g, list, 0, goals);
        }

        private boolean disjunction(Term[] args, Cont c) {
            Term left = Terms.deref(args[0]);
            if (left instanceof Struct s && s.name().equals("->") && s.arity() == 2) {
                int b = cps.size();
                cps.add(new Alt(trail.mark(), new Cont(args[1], c.cutB, goals)));
                goals = new Cont(s.arg(0), b + 1, new Cont(new Struct("$cut", new Int(b)), 0, new Cont(s.arg(1), c.cutB, goals)));
            } else {
                cps.add(new Alt(trail.mark(), new Cont(args[1], c.cutB, goals)));
                goals = new Cont(args[0], c.cutB, goals);
            }
            return true;
        }

        private Term addArgs(Term[] args) {
            Term g = Terms.deref(args[0]);
            if (args.length == 1) return g;
            if (g instanceof Var) throw new PrologError(PrologError.Kind.INSTANTIATION, "call/N: goal is unbound");
            Term[] extra = java.util.Arrays.copyOfRange(args, 1, args.length);
            if (g instanceof Atom a) return Struct.adopt(a.name(), extra);
            if (g instanceof Struct s) {
                Term[] all = new Term[s.arity() + extra.length];
                System.arraycopy(s.args, 0, all, 0, s.arity());
                System.arraycopy(extra, 0, all, s.arity(), extra.length);
                return Struct.adopt(s.name(), all);
            }
            throw new PrologError(PrologError.Kind.TYPE, "callable expected");
        }

        // ---------------------------------------------------------------- user predicates

        private boolean tryClauses(Term goal, List<Clause> list, int from, Cont next) {
            int cutB = cps.size();
            int n = list.size();
            for (int i = from; i < n; i++) {
                Clause cl = list.get(i);
                if (!quickMatch(cl.head(), goal)) continue;
                int j = i + 1;
                while (j < n && !quickMatch(list.get(j).head(), goal)) j++;
                int mark = trail.mark();
                Map<Var, Var> m = new IdentityHashMap<>();
                Term head = Terms.copy(cl.head(), m);
                if (unify(head, goal)) {
                    if (j < n) cps.add(new Clauses(mark, goal, list, j, next));
                    Term body = cl.body();
                    goals = body instanceof Atom a && a.name().equals("true") ? next : new Cont(Terms.copy(body, m), cutB, next);
                    return true;
                }
                trail.undo(mark);
            }
            return false;
        }

        /** First-argument pre-filter: false only when the clause head certainly cannot unify. */
        private boolean quickMatch(Term head, Term goal) {
            if (!(goal instanceof Struct gs)) return true;
            Term h = ((Struct) head).arg(0);
            Term g = Terms.deref(gs.arg(0));
            if (h instanceof Var || g instanceof Var) return true;
            if (h instanceof Atom ha) return g instanceof Atom ga && ga.name().equals(ha.name());
            if (h instanceof Int hi) return g instanceof Int gi && gi.value() == hi.value();
            Struct hs = (Struct) h;
            return g instanceof Struct gst && gst.arity() == hs.arity() && gst.name().equals(hs.name());
        }

        // ---------------------------------------------------------------- builtins needing the machine

        private boolean findall(Term[] args) {
            Run sub = new Run(args[1], trail, depth + 1);
            List<Term> results = new ArrayList<>();
            while (sub.next()) results.add(Terms.copy(args[0]));
            return unify(args[2], Terms.list(results));
        }

        private Clause toClause(Term t) {
            t = Terms.deref(t);
            if (t instanceof Struct s && s.name().equals(":-") && s.arity() == 2) {
                Term head = Terms.deref(s.arg(0));
                key(head);
                return new Clause(head, Terms.deref(s.arg(1)));
            }
            key(t);
            return new Clause(t, new Atom("true"));
        }

        private boolean retract(Term t) {
            Clause want = toClause(t);
            String k = key(want.head());
            if (CONTROL.contains(k) || library.contains(k)) {
                throw new PrologError(PrologError.Kind.PERMISSION, "cannot modify built-in or library predicate " + k);
            }
            List<Clause> list = db.get(k);
            if (list == null) return false;
            for (Clause cl : list) {
                int mark = trail.mark();
                Map<Var, Var> m = new IdentityHashMap<>();
                if (unify(Terms.copy(cl.head(), m), want.head()) && unify(Terms.copy(cl.body(), m), want.body())) {
                    List<Clause> fresh = new ArrayList<>(list);
                    fresh.removeIf(x -> x == cl);
                    db.put(k, List.copyOf(fresh));
                    return true;
                }
                trail.undo(mark);
            }
            return false;
        }

        private Term sorted(Term list, boolean dedup) {
            List<Term> items = new ArrayList<>(Terms.toJavaList(list));
            items.sort(Terms::compare);
            if (dedup) {
                List<Term> out = new ArrayList<>();
                for (Term x : items) if (out.isEmpty() || Terms.compare(out.get(out.size() - 1), x) != 0) out.add(x);
                items = out;
            }
            return Terms.list(items);
        }

        // ---------------------------------------------------------------- unification

        private boolean unify(Term a, Term b) {
            while (true) {
                a = Terms.deref(a);
                b = Terms.deref(b);
                if (a == b) return true;
                if (a instanceof Var va) return bind(va, b);
                if (b instanceof Var vb) return bind(vb, a);
                if (a instanceof Atom x) return b instanceof Atom y && x.name().equals(y.name());
                if (a instanceof Int x) return b instanceof Int y && x.value() == y.value();
                Struct sa = (Struct) a;
                if (!(b instanceof Struct sb) || sa.arity() != sb.arity() || !sa.name().equals(sb.name())) return false;
                int n = sa.arity();
                for (int i = 0; i < n - 1; i++) if (!unify(sa.arg(i), sb.arg(i))) return false;
                a = sa.arg(n - 1);
                b = sb.arg(n - 1);
            }
        }

        private boolean bind(Var v, Term t) {
            if (occursCheck && occurs(v, t)) return false;
            trail.bind(v, t);
            return true;
        }

        private boolean occurs(Var v, Term t) {
            while (true) {
                t = Terms.deref(t);
                if (t == v) return true;
                if (!(t instanceof Struct s)) return false;
                int n = s.arity();
                for (int i = 0; i < n - 1; i++) if (occurs(v, s.arg(i))) return true;
                t = s.arg(n - 1);
            }
        }
    }

    // ------------------------------------------------------------------ arithmetic

    private static long eval(Term t) {
        t = Terms.deref(t);
        if (t instanceof Int i) return i.value();
        if (t instanceof Var) throw new PrologError(PrologError.Kind.INSTANTIATION, "arithmetic: unbound variable");
        if (t instanceof Atom a) throw new PrologError(PrologError.Kind.TYPE, "arithmetic: not evaluable: " + a.name());
        Struct s = (Struct) t;
        try {
            if (s.arity() == 1) {
                long x = eval(s.arg(0));
                switch (s.name()) {
                    case "-": return Math.negateExact(x);
                    case "abs": return x < 0 ? Math.negateExact(x) : x;
                    default: break;
                }
            } else if (s.arity() == 2) {
                long x = eval(s.arg(0));
                long y = eval(s.arg(1));
                switch (s.name()) {
                    case "+": return Math.addExact(x, y);
                    case "-": return Math.subtractExact(x, y);
                    case "*": return Math.multiplyExact(x, y);
                    case "min": return Math.min(x, y);
                    case "max": return Math.max(x, y);
                    case "//":
                        if (y == 0) throw new PrologError(PrologError.Kind.EVALUATION, "division by zero");
                        if (x == Long.MIN_VALUE && y == -1) throw new ArithmeticException();
                        return x / y;
                    case "mod":
                        if (y == 0) throw new PrologError(PrologError.Kind.EVALUATION, "division by zero");
                        return Math.floorMod(x, y);
                    case "rem":
                        if (y == 0) throw new PrologError(PrologError.Kind.EVALUATION, "division by zero");
                        return x % y;
                    default: break;
                }
            }
        } catch (ArithmeticException e) {
            throw new PrologError(PrologError.Kind.EVALUATION, "integer overflow");
        }
        throw new PrologError(PrologError.Kind.TYPE, "arithmetic: not evaluable: " + s.name() + "/" + s.arity());
    }
}
