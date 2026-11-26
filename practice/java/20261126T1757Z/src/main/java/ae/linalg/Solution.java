package ae.linalg;

import java.util.List;

/** Result of solving {@code A x = b}. */
public sealed interface Solution {
    /** Exactly one solution. */
    record Unique(Rational[] x) implements Solution { }

    /** Infinitely many: {@code particular + sum t_i * nullBasis[i]}; particular is 0 in every free column. */
    record Infinite(Rational[] particular, List<Rational[]> nullBasis) implements Solution { }

    /** No solution. */
    record Inconsistent() implements Solution { }
}
