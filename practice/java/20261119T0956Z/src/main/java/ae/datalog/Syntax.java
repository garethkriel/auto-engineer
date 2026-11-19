package ae.datalog;

import java.util.List;

/** AST of a Datalog program. */
public final class Syntax {
    private Syntax() {}

    /** 1-based source position. */
    public record Pos(int line, int column) {}

    /** A term: variable or constant. */
    public sealed interface Term permits Var, Const {}

    /** Variable; anonymous variables are renamed {@code _1}, {@code _2}, ... by the parser. */
    public record Var(String name) implements Term {
        boolean anonymous() {
            return name.startsWith("_");
        }
    }

    /** Constant, stored as its text (quotes removed). */
    public record Const(String value) implements Term {}

    /** {@code pred(t1, ..., tn)}. */
    public record Atom(String pred, List<Term> args, Pos pos) {}

    /** Body item. */
    public sealed interface Item permits Pos_, Neg, Neq {}

    /** Positive atom. */
    public record Pos_(Atom atom) implements Item {}

    /** Negated atom. */
    public record Neg(Atom atom) implements Item {}

    /** Inequality {@code l != r}. */
    public record Neq(Term left, Term right) implements Item {}

    /** {@code head :- body.} */
    public record Rule(Atom head, List<Item> body, Pos pos) {}

    /** Parsed program. */
    public record Program(List<Atom> facts, List<Rule> rules, List<Atom> queries) {}
}
