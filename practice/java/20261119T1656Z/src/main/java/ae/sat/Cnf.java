package ae.sat;

import java.util.ArrayList;
import java.util.List;

/** A CNF formula in DIMACS convention: literal {@code +v} is variable v, {@code -v} its negation. */
public final class Cnf {
    private final int numVars;
    private final List<int[]> clauses;

    /**
     * @param numVars number of variables (literals must be in 1..numVars in absolute value)
     * @param clauses clauses to copy; may contain empty clauses, tautologies and repeated literals
     */
    public Cnf(int numVars, List<int[]> clauses) {
        if (numVars < 0) {
            throw new IllegalArgumentException("numVars must be >= 0");
        }
        List<int[]> copy = new ArrayList<>(clauses.size());
        for (int[] c : clauses) {
            for (int lit : c) {
                if (lit == 0 || Math.abs((long) lit) > numVars) {
                    throw new IllegalArgumentException("literal " + lit + " out of range for " + numVars + " variables");
                }
            }
            copy.add(c.clone());
        }
        this.numVars = numVars;
        this.clauses = copy;
    }

    public int numVars() {
        return numVars;
    }

    /** Returns a deep copy, so callers cannot mutate the formula. */
    public List<int[]> clauses() {
        List<int[]> out = new ArrayList<>(clauses.size());
        for (int[] c : clauses) {
            out.add(c.clone());
        }
        return out;
    }

    int clauseCount() {
        return clauses.size();
    }

    int[] clauseAt(int i) {
        return clauses.get(i);
    }
}
