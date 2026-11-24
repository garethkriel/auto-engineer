package ae.minilang;

import ae.minilang.Ast.*;
import ae.minilang.LangException.Kind;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static name and arity checks. Everything downstream may assume a checked program. */
final class Checker {
    private final Map<String, Integer> arity = new HashMap<>();
    private final Deque<Set<String>> scopes = new ArrayDeque<>();

    private Checker() { }

    static void check(Program p) {
        Checker c = new Checker();
        for (Func f : p.funcs()) {
            if (c.arity.put(f.name(), f.params().size()) != null) {
                throw c.error(f.pos(), Kind.NAME, "duplicate function " + f.name());
            }
        }
        for (Func f : p.funcs()) {
            c.scopes.push(new HashSet<>());
            for (String param : f.params()) {
                if (!c.scopes.peek().add(param)) {
                    throw c.error(f.pos(), Kind.NAME, "duplicate parameter " + param);
                }
            }
            c.stmts(f.body());
            c.scopes.pop();
        }
        c.scopes.push(new HashSet<>());
        c.stmts(p.main());
        c.scopes.pop();
    }

    private LangException error(Pos pos, Kind kind, String msg) {
        return new LangException(kind, pos + ": " + msg);
    }

    private void stmts(List<Stmt> body) {
        for (Stmt s : body) {
            stmt(s);
        }
    }

    private void scoped(List<Stmt> body) {
        scopes.push(new HashSet<>());
        stmts(body);
        scopes.pop();
    }

    private boolean declared(String name) {
        for (Set<String> s : scopes) {
            if (s.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private void stmt(Stmt s) {
        switch (s) {
            case Let l -> {
                expr(l.init());
                if (!scopes.peek().add(l.name())) {
                    throw error(l.pos(), Kind.NAME, "variable " + l.name() + " already declared in this scope");
                }
            }
            case Assign a -> {
                expr(a.value());
                if (!declared(a.name())) {
                    throw error(a.pos(), Kind.NAME, "unknown variable " + a.name());
                }
            }
            case If i -> {
                expr(i.cond());
                scoped(i.thenBody());
                scoped(i.elseBody());
            }
            case While w -> {
                expr(w.cond());
                scoped(w.body());
            }
            case Return r -> {
                if (r.value() != null) {
                    expr(r.value());
                }
            }
            case Print p -> expr(p.value());
            case ExprStmt e -> expr(e.expr());
            case Block b -> scoped(b.body());
        }
    }

    private void expr(Expr e) {
        switch (e) {
            case Num n -> { }
            case Var v -> {
                if (!declared(v.name())) {
                    throw error(v.pos(), Kind.NAME, "unknown variable " + v.name());
                }
            }
            case Unary u -> expr(u.operand());
            case Binary b -> {
                expr(b.left());
                expr(b.right());
            }
            case Call c -> {
                Integer want = arity.get(c.name());
                if (want == null) {
                    throw error(c.pos(), Kind.NAME, "unknown function " + c.name());
                }
                if (want != c.args().size()) {
                    throw error(c.pos(), Kind.ARITY, c.name() + " expects " + want + " argument(s) but got "
                        + c.args().size());
                }
                c.args().forEach(this::expr);
            }
        }
    }
}
