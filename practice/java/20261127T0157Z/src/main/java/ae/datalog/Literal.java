package ae.datalog;

/** A body literal. */
public sealed interface Literal {
    /** Positive atom. */
    record Pos(Atom atom) implements Literal {}

    /** Negated atom. */
    record Neg(Atom atom) implements Literal {}

    /** Equality test. */
    record Eq(Term left, Term right) implements Literal {}

    /** Inequality test. */
    record Neq(Term left, Term right) implements Literal {}
}
