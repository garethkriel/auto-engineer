package ae.hm;

import ae.hm.Type.TCon;
import ae.hm.Type.TVar;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Algorithm W with a single mutable substitution. Type variables bound by {@link #unify} live in
 * {@code bindings}; {@link #resolve} applies the substitution. The occurs check keeps it acyclic.
 */
final class Infer {

    /** forall vars. type; builtin schemes use negative variable ids so they never clash with fresh ones. */
    private record Scheme(List<Integer> vars, Type type) {}

    private record Env(String name, Scheme scheme, Env parent) {}

    private final Map<Integer, Type> bindings = new HashMap<>();
    private final String source;
    private int next = 0;
    private final Env builtins;

    Infer() {
        this(null);
    }

    Infer(String source) {
        this.source = source;
        this.builtins = builtinEnv();
    }

    TVar fresh() {
        return new TVar(next++);
    }

    /** Test hook: installs a binding without the occurs check. */
    void forceBind(TVar v, Type t) {
        bindings.put(v.id(), t);
    }

    Type infer(Expr e) {
        return resolve(infer(e, builtins));
    }

    // ---- builtins ----

    private static Env builtinEnv() {
        TVar a = new TVar(-1);
        TVar b = new TVar(-2);
        Type ii = Type.fun(Type.INT, Type.fun(Type.INT, Type.INT));
        Env env = null;
        Object[][] table = {
            {"+", List.of(), ii},
            {"-", List.of(), ii},
            {"*", List.of(), ii},
            {"==", List.of(), Type.fun(Type.INT, Type.fun(Type.INT, Type.BOOL))},
            {"not", List.of(), Type.fun(Type.BOOL, Type.BOOL)},
            {"fst", List.of(-1, -2), Type.fun(Type.pair(a, b), a)},
            {"snd", List.of(-1, -2), Type.fun(Type.pair(a, b), b)},
            {"nil", List.of(-1), Type.list(a)},
            {"cons", List.of(-1), Type.fun(a, Type.fun(Type.list(a), Type.list(a)))},
            {"head", List.of(-1), Type.fun(Type.list(a), a)},
            {"tail", List.of(-1), Type.fun(Type.list(a), Type.list(a))},
            {"isNil", List.of(-1), Type.fun(Type.list(a), Type.BOOL)},
        };
        for (Object[] row : table) {
            @SuppressWarnings("unchecked")
            List<Integer> vars = (List<Integer>) row[1];
            env = new Env((String) row[0], new Scheme(vars, (Type) row[2]), env);
        }
        return env;
    }

    // ---- inference ----

    private Type infer(Expr e, Env env) {
        switch (e) {
            case Expr.IntLit i -> {
                return Type.INT;
            }
            case Expr.BoolLit b -> {
                return Type.BOOL;
            }
            case Expr.Var v -> {
                Scheme s = lookup(env, v.name());
                if (s == null) {
                    throw error(TypeException.Kind.UNBOUND, v.pos(), "unbound variable " + v.name());
                }
                return instantiate(s);
            }
            case Expr.Lam l -> {
                TVar param = fresh();
                Type body = infer(l.body(), new Env(l.param(), new Scheme(List.of(), param), env));
                return Type.fun(param, body);
            }
            case Expr.App a -> {
                Type fn = infer(a.fn(), env);
                Type arg = infer(a.arg(), env);
                TVar result = fresh();
                unify(fn, Type.fun(arg, result), a.pos());
                return result;
            }
            case Expr.Pair p -> {
                Type l = infer(p.left(), env);
                Type r = infer(p.right(), env);
                return Type.pair(l, r);
            }
            case Expr.If f -> {
                Type c = infer(f.cond(), env);
                unify(c, Type.BOOL, f.cond().position());
                Type t = infer(f.thenBranch(), env);
                Type el = infer(f.elseBranch(), env);
                unify(t, el, f.elseBranch().position());
                return t;
            }
            case Expr.Let l -> {
                Scheme scheme;
                if (l.rec()) {
                    TVar self = fresh();
                    Env inner = new Env(l.name(), new Scheme(List.of(), self), env);
                    Type value = infer(l.value(), inner);
                    unify(self, value, l.value().position());
                    scheme = generalize(env, self);
                } else {
                    scheme = generalize(env, infer(l.value(), env));
                }
                return infer(l.body(), new Env(l.name(), scheme, env));
            }
        }
    }

    private static Scheme lookup(Env env, String name) {
        for (Env e = env; e != null; e = e.parent()) {
            if (e.name().equals(name)) {
                return e.scheme();
            }
        }
        return null;
    }

    private Type instantiate(Scheme s) {
        if (s.vars().isEmpty()) {
            return s.type();
        }
        Map<Integer, Type> fresh = new HashMap<>();
        for (int v : s.vars()) {
            fresh.put(v, fresh());
        }
        return substitute(s.type(), fresh);
    }

    private static Type substitute(Type t, Map<Integer, Type> map) {
        return switch (t) {
            case TVar v -> map.getOrDefault(v.id(), v);
            case TCon c -> {
                List<Type> args = new ArrayList<>();
                for (Type a : c.args()) {
                    args.add(substitute(a, map));
                }
                yield new TCon(c.name(), args);
            }
        };
    }

    /** Quantifies the variables of {@code t} that are not free in the environment. */
    private Scheme generalize(Env env, Type t) {
        Type resolved = resolve(t);
        Set<Integer> inEnv = new HashSet<>();
        for (Env e = env; e != null; e = e.parent()) {
            Set<Integer> free = new LinkedHashSet<>(Type.freeVars(resolve(e.scheme().type())));
            free.removeAll(e.scheme().vars());
            inEnv.addAll(free);
        }
        List<Integer> vars = new ArrayList<>();
        for (int v : Type.freeVars(resolved)) {
            if (!inEnv.contains(v)) {
                vars.add(v);
            }
        }
        return new Scheme(vars, resolved);
    }

    // ---- substitution and unification ----

    /** Applies the substitution everywhere. */
    Type resolve(Type t) {
        return switch (t) {
            case TVar v -> {
                Type bound = bindings.get(v.id());
                yield bound == null ? v : resolve(bound);
            }
            case TCon c -> {
                if (c.args().isEmpty()) {
                    yield c;
                }
                List<Type> args = new ArrayList<>();
                for (Type a : c.args()) {
                    args.add(resolve(a));
                }
                yield new TCon(c.name(), args);
            }
        };
    }

    private Type prune(Type t) {
        Type cur = t;
        while (cur instanceof TVar v && bindings.containsKey(v.id())) {
            cur = bindings.get(v.id());
        }
        return cur;
    }

    private void unify(Type x, Type y, int pos) {
        Type a = prune(x);
        Type b = prune(y);
        if (a instanceof TVar va) {
            if (b instanceof TVar vb && va.id() == vb.id()) {
                return;
            }
            bind(va, b, pos);
        } else if (b instanceof TVar vb) {
            bind(vb, a, pos);
        } else {
            TCon ca = (TCon) a;
            TCon cb = (TCon) b;
            if (!ca.name().equals(cb.name()) || ca.args().size() != cb.args().size()) {
                List<String> s = Type.showAll(resolve(ca), resolve(cb));
                throw error(TypeException.Kind.MISMATCH, pos, "cannot unify " + s.get(0) + " with " + s.get(1));
            }
            for (int k = 0; k < ca.args().size(); k++) {
                unify(ca.args().get(k), cb.args().get(k), pos);
            }
        }
    }

    private void bind(TVar v, Type t, int pos) {
        Type resolved = resolve(t);
        if (Type.freeVars(resolved).contains(v.id())) {
            List<String> s = Type.showAll(v, resolved);
            throw error(TypeException.Kind.OCCURS, pos, "occurs check: " + s.get(0) + " occurs in " + s.get(1));
        }
        bindings.put(v.id(), resolved);
    }

    private TypeException error(TypeException.Kind kind, int pos, String message) {
        int[] lc = source == null ? new int[] {1, pos + 1} : Parser.lineCol(source, pos);
        return new TypeException(kind, lc[0], lc[1], message);
    }

    // ---- invariant ----

    /**
     * Verifies that the substitution is acyclic and that resolved types mention only unbound variables.
     *
     * @throws IllegalStateException when it is not
     */
    void checkInvariant() {
        Set<Integer> done = new HashSet<>();
        for (int id : bindings.keySet()) {
            visit(id, new HashSet<>(), done);
        }
        for (int id : bindings.keySet()) {
            for (int free : Type.freeVars(resolve(new TVar(id)))) {
                if (bindings.containsKey(free)) {
                    throw new IllegalStateException("resolved type still mentions bound variable " + free);
                }
            }
        }
    }

    private void visit(int id, Set<Integer> path, Set<Integer> done) {
        if (done.contains(id)) {
            return;
        }
        if (!path.add(id)) {
            throw new IllegalStateException("cyclic substitution through variable " + id);
        }
        Type bound = bindings.get(id);
        if (bound != null) {
            for (int dep : Type.freeVars(bound)) {
                visit(dep, path, done);
            }
        }
        path.remove(id);
        done.add(id);
    }
}
