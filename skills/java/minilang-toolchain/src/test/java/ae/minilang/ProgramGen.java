package ae.minilang;

import ae.minilang.Ast.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Seeded generator of valid, terminating programs (loops use protected counters; calls only go downward). */
final class ProgramGen {
    private static final long[] BIG = {Long.MAX_VALUE, Long.MAX_VALUE - 1, 4611686018427387904L, 3037000500L};
    private static final BinOp[] STRICT = {BinOp.ADD, BinOp.SUB, BinOp.MUL, BinOp.DIV, BinOp.MOD, BinOp.LT,
        BinOp.LE, BinOp.GT, BinOp.GE, BinOp.EQ, BinOp.NE};

    private final Random rnd;
    private final List<Func> funcs = new ArrayList<>();
    private int nameCounter;
    private int nFuncsDone;

    /** Per-function generation state: visible variables, protected counters, loop nesting. */
    private static final class Ctx {
        final List<String> visible = new ArrayList<>();
        final Set<String> locked = new HashSet<>();
        final Set<String> declaredHere = new HashSet<>();
        int loops;
    }

    ProgramGen(long seed) {
        this.rnd = new Random(seed);
    }

    Program generate() {
        int nf = rnd.nextInt(4);
        for (int i = 0; i < nf; i++) {
            int arity = rnd.nextInt(3);
            List<String> params = new ArrayList<>();
            Ctx c = new Ctx();
            for (int k = 0; k < arity; k++) {
                String p = "p" + k;
                params.add(p);
                c.visible.add(p);
                c.declaredHere.add(p);
            }
            nFuncsDone = i;
            List<Stmt> body = new ArrayList<>(block(c, 2, 1 + rnd.nextInt(3), false));
            if (rnd.nextInt(3) > 0) {
                body.add(new Return(expr(c, 2)));
            }
            funcs.add(new Func("f" + i, List.copyOf(params), List.copyOf(body), new Pos(0, 0)));
        }
        nFuncsDone = nf;
        Ctx main = new Ctx();
        List<Stmt> body = new ArrayList<>(block(main, 3, 2 + rnd.nextInt(4), false));
        body.add(new Print(expr(main, 2)));
        return new Program(List.copyOf(funcs), List.copyOf(body));
    }

    private String freshName() {
        return "v" + nameCounter++;
    }

    /** Statements for one scope; {@code c.declaredHere} is swapped in and out around it. */
    private List<Stmt> block(Ctx c, int depth, int count) {
        return block(c, depth, count, true);
    }

    /** {@code nested} false keeps the caller's declared set (function parameters share the body scope). */
    private List<Stmt> block(Ctx c, int depth, int count, boolean nested) {
        List<Stmt> out = new ArrayList<>();
        int visibleBefore = c.visible.size();
        Set<String> outerDeclared = new HashSet<>(c.declaredHere);
        if (nested) {
            c.declaredHere.clear();
        }
        for (int i = 0; i < count; i++) {
            out.add(stmt(c, depth));
            // a Let/loop statement may append to visible; a loop returns two statements through stmt
        }
        while (c.visible.size() > visibleBefore) {
            c.visible.remove(c.visible.size() - 1);
        }
        c.declaredHere.clear();
        c.declaredHere.addAll(outerDeclared);
        return out;
    }

    private Stmt stmt(Ctx c, int depth) {
        int roll = rnd.nextInt(100);
        if (roll < 22 || depth <= 0 && roll < 60) {
            // new variable, shadowing an outer one half of the time when legal
            String name;
            List<String> outer = new ArrayList<>(c.visible);
            outer.removeAll(c.declaredHere);
            if (!outer.isEmpty() && rnd.nextBoolean() && !c.locked.contains(outer.get(0))) {
                name = outer.get(rnd.nextInt(outer.size()));
                if (c.locked.contains(name)) {
                    name = freshName();
                }
            } else {
                name = freshName();
            }
            Expr init = expr(c, 2);
            c.visible.add(name);
            c.declaredHere.add(name);
            return new Let(name, init, new Pos(0, 0));
        }
        if (roll < 40) {
            List<String> targets = new ArrayList<>(c.visible);
            targets.removeAll(c.locked);
            if (!targets.isEmpty()) {
                return new Assign(targets.get(rnd.nextInt(targets.size())), expr(c, 2), new Pos(0, 0));
            }
        }
        if (roll < 58 || depth <= 0) {
            return new Print(expr(c, 2));
        }
        if (roll < 74) {
            return new If(expr(c, 2), block(c, depth - 1, 1 + rnd.nextInt(2)),
                rnd.nextBoolean() ? List.of() : block(c, depth - 1, 1 + rnd.nextInt(2)));
        }
        if (roll < 88 && c.loops < 2) {
            String counter = freshName();
            c.loops++;
            c.visible.add(counter);
            c.locked.add(counter);
            c.declaredHere.add(counter);
            List<Stmt> body = new ArrayList<>(block(c, depth - 1, 1 + rnd.nextInt(2)));
            body.add(new Assign(counter, new Binary(BinOp.ADD, new Var(counter, new Pos(0, 0)), new Num(1)),
                new Pos(0, 0)));
            c.loops--;
            // wrap `let counter = 0; while (counter < K) {...}` in a block so the counter stays out of scope
            Stmt loop = new While(new Binary(BinOp.LT, new Var(counter, new Pos(0, 0)),
                new Num(rnd.nextInt(5))), List.copyOf(body));
            c.visible.remove(counter);
            c.locked.remove(counter);
            c.declaredHere.remove(counter);
            return new Block(List.of(new Let(counter, new Num(0), new Pos(0, 0)), loop));
        }
        if (roll < 93) {
            return new ExprStmt(expr(c, 2));
        }
        if (roll < 97) {
            return new Block(block(c, depth - 1, 1 + rnd.nextInt(2)));
        }
        return new If(expr(c, 1), List.of(new Return(rnd.nextBoolean() ? null : expr(c, 1))), List.of());
    }

    private Expr expr(Ctx c, int depth) {
        int roll = rnd.nextInt(100);
        if (depth <= 0 || roll < 20) {
            return leaf(c);
        }
        if (roll < 28) {
            return new Unary(rnd.nextBoolean() ? UnOp.NEG : UnOp.NOT, expr(c, depth - 1));
        }
        if (roll < 40 && nFuncsDone > 0) {
            Func f = funcs.get(rnd.nextInt(Math.min(nFuncsDone, funcs.size())));
            List<Expr> args = new ArrayList<>();
            for (int i = 0; i < f.params().size(); i++) {
                args.add(expr(c, depth - 1));
            }
            return new Call(f.name(), List.copyOf(args), new Pos(0, 0));
        }
        if (roll < 52) {
            return new Binary(rnd.nextBoolean() ? BinOp.AND : BinOp.OR, expr(c, depth - 1), expr(c, depth - 1));
        }
        BinOp op = STRICT[rnd.nextInt(STRICT.length)];
        Expr right = expr(c, depth - 1);
        if ((op == BinOp.DIV || op == BinOp.MOD) && rnd.nextInt(8) == 0) {
            right = rnd.nextBoolean() ? new Num(0) : new Binary(BinOp.SUB, leaf(c), leaf(c));
        }
        return new Binary(op, expr(c, depth - 1), right);
    }

    private Expr leaf(Ctx c) {
        int roll = rnd.nextInt(100);
        if (roll < 40 && !c.visible.isEmpty()) {
            return new Var(c.visible.get(rnd.nextInt(c.visible.size())), new Pos(0, 0));
        }
        if (roll < 46) {
            return new Num(BIG[rnd.nextInt(BIG.length)]);
        }
        if (roll < 52) {
            return new Unary(UnOp.NEG, new Num(BIG[rnd.nextInt(BIG.length)]));
        }
        return new Num(rnd.nextInt(14) - 2 < 0 ? 0 : rnd.nextInt(14));
    }
}
