package ae.minilang;

import ae.minilang.Ast.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Semantics-preserving AST simplifier. Rules: fold constants only when evaluation cannot throw; never drop an
 * expression that may throw, print or call; drop only dead branches, pure statements and code after return.
 */
final class Optimizer {
    private Optimizer() { }

    /** Applies the rewrites until the program stops changing. */
    static Program optimize(Program p) {
        Program cur = p;
        while (true) {
            Program next = pass(cur);
            if (next.equals(cur)) {
                return next;
            }
            cur = next;
        }
    }

    private static Program pass(Program p) {
        List<Func> fs = new ArrayList<>();
        for (Func f : p.funcs()) {
            fs.add(new Func(f.name(), f.params(), stmts(f.body()), f.pos()));
        }
        return new Program(List.copyOf(fs), stmts(p.main()));
    }

    private static List<Stmt> stmts(List<Stmt> body) {
        List<Stmt> out = new ArrayList<>();
        for (Stmt s : body) {
            for (Stmt t : stmt(s)) {
                out.add(t);
                if (t instanceof Return) {
                    return List.copyOf(out);
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean pure(Expr e) {
        return e instanceof Num || e instanceof Var;
    }

    private static List<Stmt> stmt(Stmt s) {
        return switch (s) {
            case Let l -> List.of(new Let(l.name(), expr(l.init()), l.pos()));
            case Assign a -> List.of(new Assign(a.name(), expr(a.value()), a.pos()));
            case Print p -> List.of(new Print(expr(p.value())));
            case Return r -> List.of(new Return(r.value() == null ? null : expr(r.value())));
            case ExprStmt e -> {
                Expr x = expr(e.expr());
                yield pure(x) ? List.of() : List.of(new ExprStmt(x));
            }
            case Block b -> {
                List<Stmt> body = stmts(b.body());
                if (body.isEmpty()) {
                    yield List.of();
                }
                boolean declares = body.stream().anyMatch(t -> t instanceof Let);
                yield declares ? List.of(new Block(body)) : body;
            }
            case While w -> {
                Expr c = expr(w.cond());
                if (c instanceof Num n && n.value() == 0) {
                    yield List.of();
                }
                yield List.of(new While(c, stmts(w.body())));
            }
            case If i -> {
                Expr c = expr(i.cond());
                List<Stmt> t = stmts(i.thenBody());
                List<Stmt> e = stmts(i.elseBody());
                if (c instanceof Num n) {
                    List<Stmt> chosen = n.value() != 0 ? t : e;
                    yield chosen.isEmpty() ? List.of() : List.of(new Block(chosen));
                }
                if (t.isEmpty() && e.isEmpty()) {
                    yield stmt(new ExprStmt(c));
                }
                yield List.of(new If(c, t, e));
            }
        };
    }

    private static boolean isNum(Expr e, long v) {
        return e instanceof Num n && n.value() == v;
    }

    private static boolean isBool(Expr e) {
        return switch (e) {
            case Unary u -> u.op() == UnOp.NOT;
            case Binary b -> b.op() != BinOp.ADD && b.op() != BinOp.SUB && b.op() != BinOp.MUL
                && b.op() != BinOp.DIV && b.op() != BinOp.MOD;
            case Num n -> n.value() == 0 || n.value() == 1;
            default -> false;
        };
    }

    /** The 0/1 truth value of {@code e}. */
    private static Expr truth(Expr e) {
        if (e instanceof Num n) {
            return new Num(n.value() != 0 ? 1 : 0);
        }
        return isBool(e) ? e : new Binary(BinOp.NE, e, new Num(0));
    }

    static Expr expr(Expr e) {
        return switch (e) {
            case Num n -> n;
            case Var v -> v;
            case Call c -> new Call(c.name(), c.args().stream().map(Optimizer::expr).toList(), c.pos());
            case Unary u -> {
                Expr x = expr(u.operand());
                if (x instanceof Num n) {
                    if (u.op() == UnOp.NOT) {
                        yield new Num(Arith.not(n.value()));
                    }
                    if (n.value() != Long.MIN_VALUE) {
                        yield new Num(-n.value());
                    }
                }
                yield new Unary(u.op(), x);
            }
            case Binary b -> binary(b);
        };
    }

    private static Expr binary(Binary b) {
        Expr l = expr(b.left());
        Expr r = expr(b.right());
        switch (b.op()) {
            case AND -> {
                if (l instanceof Num n) {
                    return n.value() == 0 ? new Num(0) : truth(r);
                }
                return new Binary(BinOp.AND, l, r);
            }
            case OR -> {
                if (l instanceof Num n) {
                    return n.value() != 0 ? new Num(1) : truth(r);
                }
                return new Binary(BinOp.OR, l, r);
            }
            default -> { }
        }
        if (l instanceof Num a && r instanceof Num c) {
            try {
                return new Num(Arith.binary(b.op(), a.value(), c.value()));
            } catch (LangException ex) {
                return new Binary(b.op(), l, r);
            }
        }
        switch (b.op()) {
            case ADD -> {
                if (isNum(r, 0)) {
                    return l;
                }
                if (isNum(l, 0)) {
                    return r;
                }
            }
            case SUB -> {
                if (isNum(r, 0)) {
                    return l;
                }
            }
            case MUL -> {
                if (isNum(r, 1)) {
                    return l;
                }
                if (isNum(l, 1)) {
                    return r;
                }
            }
            case DIV -> {
                if (isNum(r, 1)) {
                    return l;
                }
            }
            default -> { }
        }
        return new Binary(b.op(), l, r);
    }
}
