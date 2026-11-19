package ae.sat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CDCL SAT solver: two-watched-literal propagation, first-UIP learning, non-chronological backjumping,
 * VSIDS-style activities, phase saving and Luby restarts. Deterministic (ties break on the lowest variable).
 * A solver instance answers one query; build a new one to solve again.
 */
public final class Solver {
    private static final int RESTART_UNIT = 32;
    private static final double VAR_DECAY = 0.95;

    /** Growable int array used for watch lists and the trail. */
    private static final class IntList {
        int[] a = new int[4];
        int size;

        void add(int x) {
            if (size == a.length) {
                a = Arrays.copyOf(a, size * 2);
            }
            a[size++] = x;
        }
    }

    private final int n;
    private final List<int[]> clauses = new ArrayList<>();
    private final IntList[] watches; // indexed by literal: clauses that watch that literal
    private final byte[] value; // per variable: 0 unassigned, 1 true, -1 false
    private final int[] level;
    private final int[] reason; // clause index or -1
    private final int[] trail;
    private int trailSize;
    private int qhead;
    private final IntList trailLim = new IntList();
    private final double[] activity;
    private double varInc = 1.0;
    private final boolean[] phase;
    private final boolean[] seen;
    private final List<int[]> proof = new ArrayList<>();
    private boolean rootConflict;
    private long conflicts;
    private long decisions;
    private long propagations;
    private boolean used;

    public Solver(Cnf cnf) {
        this.n = cnf.numVars();
        this.watches = new IntList[2 * n + 2];
        for (int i = 0; i < watches.length; i++) {
            watches[i] = new IntList();
        }
        this.value = new byte[n + 1];
        this.level = new int[n + 1];
        this.reason = new int[n + 1];
        this.trail = new int[n + 1];
        this.activity = new double[n + 1];
        this.phase = new boolean[n + 1];
        this.seen = new boolean[n + 1];
        load(cnf);
    }

    private int idx(int lit) {
        return lit > 0 ? 2 * lit : -2 * lit + 1;
    }

    private int val(int lit) {
        byte v = value[Math.abs(lit)];
        return lit > 0 ? v : -v;
    }

    private void load(Cnf cnf) {
        for (int i = 0; i < cnf.clauseCount(); i++) {
            int[] src = cnf.clauseAt(i);
            int[] c = dedupe(src);
            if (c == null) {
                continue; // tautology
            }
            if (c.length == 0) {
                rootConflict = true;
            } else if (c.length == 1) {
                if (val(c[0]) == -1) {
                    rootConflict = true;
                } else if (val(c[0]) == 0) {
                    enqueue(c[0], -1);
                }
            } else {
                attach(c);
            }
        }
    }

    /** Removes repeated literals; returns null for a tautology. */
    private static int[] dedupe(int[] c) {
        int[] out = new int[c.length];
        int k = 0;
        outer:
        for (int lit : c) {
            for (int j = 0; j < k; j++) {
                if (out[j] == lit) {
                    continue outer;
                }
                if (out[j] == -lit) {
                    return null;
                }
            }
            out[k++] = lit;
        }
        return Arrays.copyOf(out, k);
    }

    private int attach(int[] c) {
        int ci = clauses.size();
        clauses.add(c);
        watches[idx(c[0])].add(ci);
        watches[idx(c[1])].add(ci);
        return ci;
    }

    private void enqueue(int lit, int why) {
        int v = Math.abs(lit);
        value[v] = (byte) (lit > 0 ? 1 : -1);
        level[v] = trailLim.size;
        reason[v] = why;
        trail[trailSize++] = lit;
    }

    /** Propagates the queue; returns the conflicting clause index or -1. */
    private int propagate() {
        while (qhead < trailSize) {
            int p = trail[qhead++];
            propagations++;
            int falseLit = -p;
            IntList ws = watches[idx(falseLit)];
            int i = 0;
            int j = 0;
            while (i < ws.size) {
                int ci = ws.a[i++];
                int[] c = clauses.get(ci);
                if (c[0] == falseLit) {
                    c[0] = c[1];
                    c[1] = falseLit;
                }
                if (val(c[0]) == 1) {
                    ws.a[j++] = ci;
                    continue;
                }
                boolean moved = false;
                for (int k = 2; k < c.length; k++) {
                    if (val(c[k]) != -1) {
                        c[1] = c[k];
                        c[k] = falseLit;
                        watches[idx(c[1])].add(ci);
                        moved = true;
                        break;
                    }
                }
                if (moved) {
                    continue;
                }
                ws.a[j++] = ci;
                if (val(c[0]) == -1) {
                    while (i < ws.size) {
                        ws.a[j++] = ws.a[i++];
                    }
                    ws.size = j;
                    qhead = trailSize;
                    return ci;
                }
                enqueue(c[0], ci);
            }
            ws.size = j;
        }
        return -1;
    }

    private void backtrack(int target) {
        if (trailLim.size <= target) {
            return;
        }
        int keep = trailLim.a[target];
        for (int i = trailSize - 1; i >= keep; i--) {
            int v = Math.abs(trail[i]);
            phase[v] = trail[i] > 0;
            value[v] = 0;
            reason[v] = -1;
        }
        trailSize = keep;
        qhead = keep;
        trailLim.size = target;
    }

    private void bump(int v) {
        activity[v] += varInc;
        if (activity[v] > 1e100) {
            for (int i = 1; i <= n; i++) {
                activity[i] *= 1e-100;
            }
            varInc *= 1e-100;
        }
    }

    /** First-UIP analysis. Returns the learned clause with the asserting literal first and the highest-level
     * other literal second; {@code outLevel[0]} receives the backjump level. */
    private int[] analyze(int conflict, int[] outLevel) {
        int cur = trailLim.size;
        IntList learnt = new IntList();
        learnt.add(0); // slot for the asserting literal
        int pathC = 0;
        int p = 0;
        int ti = trailSize - 1;
        int cl = conflict;
        do {
            int[] c = clauses.get(cl);
            for (int q : c) {
                int v = Math.abs(q);
                if (p != 0 && v == Math.abs(p)) {
                    continue;
                }
                if (!seen[v] && level[v] > 0) {
                    bump(v);
                    seen[v] = true;
                    if (level[v] >= cur) {
                        pathC++;
                    } else {
                        learnt.add(q);
                    }
                }
            }
            while (!seen[Math.abs(trail[ti])]) {
                ti--;
            }
            p = trail[ti];
            ti--;
            cl = reason[Math.abs(p)];
            seen[Math.abs(p)] = false;
            pathC--;
        } while (pathC > 0);
        learnt.a[0] = -p;
        int[] out = Arrays.copyOf(learnt.a, learnt.size);
        int best = 1; // index of the highest-level literal among out[1..]
        for (int i = 1; i < out.length; i++) {
            seen[Math.abs(out[i])] = false;
            if (level[Math.abs(out[i])] > level[Math.abs(out[best])]) {
                best = i;
            }
        }
        int btLevel = 0;
        if (out.length > 1) {
            int tmp = out[1];
            out[1] = out[best];
            out[best] = tmp;
            btLevel = level[Math.abs(out[1])];
        }
        outLevel[0] = btLevel;
        varInc /= VAR_DECAY;
        return out;
    }

    private int pickBranchLiteral() {
        int best = 0;
        for (int v = 1; v <= n; v++) {
            if (value[v] == 0 && (best == 0 || activity[v] > activity[best])) {
                best = v;
            }
        }
        return best == 0 ? 0 : (phase[best] ? best : -best);
    }

    private static long luby(long i) {
        // i is 1-based: 1,1,2,1,1,2,4,1,1,2,...
        long k = 1;
        while ((1L << k) - 1 < i) {
            k++;
        }
        while (true) {
            if (i == (1L << k) - 1) {
                return 1L << (k - 1);
            }
            i = i - ((1L << (k - 1)) - 1);
            k = 1;
            while ((1L << k) - 1 < i) {
                k++;
            }
        }
    }

    /** Solves without a conflict limit. */
    public Result solve() {
        return solve(Long.MAX_VALUE);
    }

    /**
     * Solves, giving up with {@link Status#UNKNOWN} once {@code conflictLimit} conflicts have occurred.
     *
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException if this solver already ran
     */
    public Result solve(long conflictLimit) {
        if (conflictLimit <= 0) {
            throw new IllegalArgumentException("conflictLimit must be > 0");
        }
        if (used) {
            throw new IllegalStateException("solver instance is single-use");
        }
        used = true;
        if (rootConflict || propagate() >= 0) {
            return unsat();
        }
        long restartNo = 1;
        long untilRestart = luby(restartNo) * RESTART_UNIT;
        int[] btl = new int[1];
        while (true) {
            int conflict = propagate();
            if (conflict >= 0) {
                conflicts++;
                if (trailLim.size == 0) {
                    return unsat();
                }
                int[] learnt = analyze(conflict, btl);
                backtrack(btl[0]);
                proof.add(learnt.clone());
                if (learnt.length == 1) {
                    enqueue(learnt[0], -1);
                } else {
                    int ci = attach(learnt);
                    enqueue(learnt[0], ci);
                }
                if (conflicts >= conflictLimit) {
                    return new Result(Status.UNKNOWN, null, List.of(), conflicts, decisions, propagations);
                }
                untilRestart--;
            } else {
                if (untilRestart <= 0) {
                    backtrack(0);
                    restartNo++;
                    untilRestart = luby(restartNo) * RESTART_UNIT;
                    continue;
                }
                int lit = pickBranchLiteral();
                if (lit == 0) {
                    boolean[] model = new boolean[n + 1];
                    for (int v = 1; v <= n; v++) {
                        model[v] = value[v] == 1;
                    }
                    return new Result(Status.SAT, model, List.of(), conflicts, decisions, propagations);
                }
                decisions++;
                trailLim.add(trailSize);
                enqueue(lit, -1);
            }
        }
    }

    private Result unsat() {
        proof.add(new int[0]);
        return new Result(Status.UNSAT, null, List.copyOf(proof), conflicts, decisions, propagations);
    }
}
