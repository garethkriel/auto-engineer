package ae.sat;

/** Independent verification of a satisfying assignment. */
public final class ModelChecker {
    private ModelChecker() {
    }

    /** True when {@code model} (index = variable, length numVars+1) makes every clause true. */
    public static boolean satisfies(Cnf cnf, boolean[] model) {
        if (model == null || model.length != cnf.numVars() + 1) {
            return false;
        }
        for (int i = 0; i < cnf.clauseCount(); i++) {
            boolean ok = false;
            for (int lit : cnf.clauseAt(i)) {
                if (model[Math.abs(lit)] == (lit > 0)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
