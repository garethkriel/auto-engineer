package ae.lp;

/** Outcome of solving an LP, each variant carrying a certificate checkable against the model alone. */
public sealed interface Solution {
    /** Optimal: primal point {@code x}, its objective {@code value}, and one dual value per constraint. */
    record Optimal(Rational value, Rational[] x, Rational[] duals) implements Solution { }

    /** Infeasible: a Farkas vector {@code y} (one entry per constraint). */
    record Infeasible(Rational[] farkas) implements Solution { }

    /** Unbounded: feasible {@code point} and an improving recession {@code ray}. */
    record Unbounded(Rational[] point, Rational[] ray) implements Solution { }
}
