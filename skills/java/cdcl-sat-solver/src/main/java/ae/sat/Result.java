package ae.sat;

import java.util.List;

/**
 * Solver answer. {@code model[v]} is the value of variable v when SAT (index 0 unused, otherwise null).
 * {@code proof} is the learned-clause sequence ending in the empty clause when UNSAT (otherwise empty).
 */
public record Result(Status status, boolean[] model, List<int[]> proof, long conflicts, long decisions,
        long propagations) {
}
