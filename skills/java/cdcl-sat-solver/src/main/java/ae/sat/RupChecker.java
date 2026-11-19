package ae.sat;

import java.util.ArrayList;
import java.util.List;

/**
 * Independent checker for reverse-unit-propagation (RUP) refutations. It shares no code with {@link Solver}:
 * a clause is accepted when assuming all its literals false and propagating units over the original clauses plus
 * the earlier proof clauses reaches a conflict.
 */
public final class RupChecker {
    private RupChecker() {
    }

    /** True when every proof clause is RUP and the last one is the empty clause. */
    public static boolean check(Cnf cnf, List<int[]> proof) {
        if (proof.isEmpty() || proof.get(proof.size() - 1).length != 0) {
            return false;
        }
        List<int[]> db = new ArrayList<>(cnf.clauses());
        for (int[] step : proof) {
            if (!isRup(cnf.numVars(), db, step)) {
                return false;
            }
            db.add(step.clone());
        }
        return true;
    }

    private static boolean isRup(int numVars, List<int[]> db, int[] clause) {
        // value[v]: 0 unknown, 1 true, -1 false
        byte[] value = new byte[numVars + 1];
        for (int lit : clause) {
            int v = Math.abs(lit);
            if (v == 0 || v > numVars) {
                return false;
            }
            byte want = (byte) (lit > 0 ? -1 : 1);
            if (value[v] == -want) {
                return true; // clause is a tautology: trivially implied
            }
            value[v] = want;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int[] c : db) {
                int unassigned = 0;
                int unit = 0;
                boolean satisfied = false;
                for (int lit : c) {
                    byte val = value[Math.abs(lit)];
                    if (val == 0) {
                        unassigned++;
                        unit = lit;
                    } else if ((val == 1) == (lit > 0)) {
                        satisfied = true;
                        break;
                    }
                }
                if (satisfied) {
                    continue;
                }
                if (unassigned == 0) {
                    return true; // conflict
                }
                if (unassigned == 1) {
                    value[Math.abs(unit)] = (byte) (unit > 0 ? 1 : -1);
                    changed = true;
                }
            }
        }
        return false;
    }
}
