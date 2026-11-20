package ae.minilang;

import ae.minilang.Ast.*;
import ae.minilang.LangException.Kind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reference tree-walking interpreter. Assumes a checked program. */
final class Interp {
    static final int MAX_DEPTH = 100;

    private final Map<String, Func> funcs = new HashMap<>();
    private final List<Long> out;
    private final long limit;
    private long steps;

    /** One call: a scope stack of variable cells, plus the pending return state. */
    private static final class Frame {
        final Deque<Map<String, long[]>> scopes = new ArrayDeque<>();
        boolean returned;
        long value;
    }

    private Interp(Program p, List<Long> out, long limit) {
        for (Func f : p.funcs()) {
            funcs.put(f.name(), f);
        }
        this.out = out;
        this.limit = limit;
    }

    /** Runs main; output accumulates in {@code out} even when an error is thrown. */
    static void run(Program p, List<Long> out, long stepLimit) {
        Interp in = new Interp(p, out, stepLimit);
        Frame main = new Frame();
        main.scopes.push(new HashMap<>());
        in.execBody(p.main(), main, 1);
    }

    private void tick() {
        if (++steps > limit) {
            throw new LangException(Kind.STEP_LIMIT, "step budget exhausted");
        }
    }

    private void execScoped(List<Stmt> body, Frame f, int depth) {
        f.scopes.push(new HashMap<>());
        execBody(body, f, depth);
        f.scopes.pop();
    }

    private void execBody(List<Stmt> body, Frame f, int depth) {
        for (Stmt s : body) {
            exec(s, f, depth);
            if (f.returned) {
                return;
            }
        }
    }

    private long[] cell(Frame f, String name) {
        for (Map<String, long[]> scope : f.scopes) {
            long[] c = scope.get(name);
            if (c != null) {
                return c;
            }
        }
        throw new IllegalStateException("unchecked variable " + name);
    }

    private void exec(Stmt s, Frame f, int depth) {
        tick();
        switch (s) {
            case Let l -> {
                long v = eval(l.init(), f, depth);
                f.scopes.peek().put(l.name(), new long[] {v});
            }
            case Assign a -> cell(f, a.name())[0] = eval(a.value(), f, depth);
            case If i -> execScoped(eval(i.cond(), f, depth) != 0 ? i.thenBody() : i.elseBody(), f, depth);
            case While w -> {
                while (eval(w.cond(), f, depth) != 0) {
                    execScoped(w.body(), f, depth);
                    if (f.returned) {
                        return;
                    }
                    tick();
                }
            }
            case Return r -> {
                f.value = r.value() == null ? 0 : eval(r.value(), f, depth);
                f.returned = true;
            }
            case Print p -> out.add(eval(p.value(), f, depth));
            case ExprStmt e -> eval(e.expr(), f, depth);
            case Block b -> execScoped(b.body(), f, depth);
        }
    }

    private long eval(Expr e, Frame f, int depth) {
        tick();
        return switch (e) {
            case Num n -> n.value();
            case Var v -> cell(f, v.name())[0];
            case Unary u -> {
                long x = eval(u.operand(), f, depth);
                yield u.op() == UnOp.NEG ? Arith.neg(x) : Arith.not(x);
            }
            case Binary b -> switch (b.op()) {
                case AND -> eval(b.left(), f, depth) != 0 && eval(b.right(), f, depth) != 0 ? 1 : 0;
                case OR -> eval(b.left(), f, depth) != 0 || eval(b.right(), f, depth) != 0 ? 1 : 0;
                default -> {
                    long l = eval(b.left(), f, depth);
                    long r = eval(b.right(), f, depth);
                    yield Arith.binary(b.op(), l, r);
                }
            };
            case Call c -> call(c, f, depth);
        };
    }

    private long call(Call c, Frame caller, int depth) {
        List<Long> args = new ArrayList<>();
        for (Expr a : c.args()) {
            args.add(eval(a, caller, depth));
        }
        if (depth + 1 > MAX_DEPTH) {
            throw new LangException(Kind.STACK_OVERFLOW, "call depth exceeds " + MAX_DEPTH);
        }
        Func fn = funcs.get(c.name());
        Frame f = new Frame();
        Map<String, long[]> top = new HashMap<>();
        for (int i = 0; i < args.size(); i++) {
            top.put(fn.params().get(i), new long[] {args.get(i)});
        }
        f.scopes.push(top);
        execBody(fn.body(), f, depth + 1);
        return f.value;
    }
}
