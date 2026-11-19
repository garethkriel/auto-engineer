package ae.hm;

/** Abstract syntax of the tiny language. Every node carries the source offset used for error positions. */
public sealed interface Expr {

    record Var(String name, int pos) implements Expr {}

    record IntLit(int value, int pos) implements Expr {}

    record BoolLit(boolean value, int pos) implements Expr {}

    /** One-parameter lambda; {@code \x y -> e} is parsed as nested lambdas. */
    record Lam(String param, Expr body, int pos) implements Expr {}

    record App(Expr fn, Expr arg, int pos) implements Expr {}

    /** {@code let [rec] name = value in body}. */
    record Let(String name, Expr value, Expr body, boolean rec, int pos) implements Expr {}

    record If(Expr cond, Expr thenBranch, Expr elseBranch, int pos) implements Expr {}

    record Pair(Expr left, Expr right, int pos) implements Expr {}

    /** Source offset of the node (see TASK.md for the position conventions). */
    default int position() {
        return switch (this) {
            case Var v -> v.pos();
            case IntLit i -> i.pos();
            case BoolLit b -> b.pos();
            case Lam l -> l.pos();
            case App a -> a.pos();
            case Let l -> l.pos();
            case If f -> f.pos();
            case Pair p -> p.pos();
        };
    }

    /** Fully parenthesised source form that {@link Parser#parse} reads back as the same term. */
    default String show() {
        return switch (this) {
            case Var v -> v.name();
            case IntLit i -> Integer.toString(i.value());
            case BoolLit b -> Boolean.toString(b.value());
            case Lam l -> "(\\" + l.param() + " -> " + l.body().show() + ")";
            case App a -> {
                if (a.fn() instanceof App inner && inner.fn() instanceof Var op && Parser.isOperator(op.name())) {
                    yield "(" + inner.arg().show() + " " + op.name() + " " + a.arg().show() + ")";
                }
                yield "(" + a.fn().show() + " " + a.arg().show() + ")";
            }
            case Let l -> "(let " + (l.rec() ? "rec " : "") + l.name() + " = " + l.value().show()
                + " in " + l.body().show() + ")";
            case If f -> "(if " + f.cond().show() + " then " + f.thenBranch().show() + " else " + f.elseBranch().show() + ")";
            case Pair p -> "(" + p.left().show() + ", " + p.right().show() + ")";
        };
    }
}
