package ae.lp;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact two-phase tableau simplex with Bland's anti-cycling rule.
 *
 * <p>Internally every row is scaled by {@code s_i = +-1} so its right-hand side is non-negative;
 * duals and Farkas vectors are recovered from the final basis by solving {@code B^T w = c_B}
 * and mapping back with {@code y_i = s_i * w_i}.
 */
public final class Simplex {
    private final int n;
    private final int m;
    private final int cols;
    private final int firstArt;
    private final int[] scale;
    private final Rational[][] a0;
    private final Rational[][] t;
    private final int[] basis;
    private final boolean[] active;
    private final LpModel model;

    private Simplex(LpModel model) {
        this.model = model;
        n = model.variables().size();
        m = model.constraints().size();
        scale = new int[m];
        LpModel.Op[] op = new LpModel.Op[m];
        int slacks = 0;
        int arts = 0;
        for (int i = 0; i < m; i++) {
            LpModel.Constraint c = model.constraints().get(i);
            scale[i] = c.rhs().signum() < 0 ? -1 : 1;
            op[i] = scale[i] > 0 ? c.op() : flip(c.op());
            if (op[i] != LpModel.Op.EQ) {
                slacks++;
            }
            if (op[i] != LpModel.Op.LE) {
                arts++;
            }
        }
        firstArt = n + slacks;
        cols = firstArt + arts;
        a0 = new Rational[m][cols + 1];
        basis = new int[m];
        active = new boolean[m];
        int slack = n;
        int art = firstArt;
        for (int i = 0; i < m; i++) {
            LpModel.Constraint c = model.constraints().get(i);
            Rational s = Rational.of(scale[i]);
            java.util.Arrays.fill(a0[i], Rational.ZERO);
            Rational[] coef = c.coef();
            for (int j = 0; j < n; j++) {
                a0[i][j] = coef[j].mul(s);
            }
            a0[i][cols] = c.rhs().mul(s);
            active[i] = true;
            switch (op[i]) {
                case LE -> {
                    a0[i][slack] = Rational.ONE;
                    basis[i] = slack++;
                }
                case GE -> {
                    a0[i][slack++] = Rational.ONE.negate();
                    a0[i][art] = Rational.ONE;
                    basis[i] = art++;
                }
                case EQ -> {
                    a0[i][art] = Rational.ONE;
                    basis[i] = art++;
                }
            }
        }
        t = new Rational[m][];
        for (int i = 0; i < m; i++) {
            t[i] = a0[i].clone();
        }
    }

    private static LpModel.Op flip(LpModel.Op op) {
        return switch (op) {
            case LE -> LpModel.Op.GE;
            case GE -> LpModel.Op.LE;
            case EQ -> LpModel.Op.EQ;
        };
    }

    /** Solves the model exactly. */
    public static Solution solve(LpModel model) {
        return new Simplex(model).run();
    }

    private Solution run() {
        if (firstArt < cols) {
            Rational[] cost1 = new Rational[cols];
            java.util.Arrays.fill(cost1, Rational.ZERO);
            for (int j = firstArt; j < cols; j++) {
                cost1[j] = Rational.ONE.negate();
            }
            iterate(cost1); // phase 1 is bounded above by 0, so it always ends optimal
            Rational residual = Rational.ZERO;
            for (int i = 0; i < m; i++) {
                if (basis[i] >= firstArt) {
                    residual = residual.add(t[i][cols]);
                }
            }
            if (residual.signum() > 0) {
                Rational[] w = duals(cost1);
                Rational[] y = new Rational[m];
                for (int i = 0; i < m; i++) {
                    y[i] = w[i].mul(Rational.of(scale[i]));
                }
                return new Solution.Infeasible(y);
            }
            driveOutArtificials();
        }
        boolean max = model.sense() == LpModel.Sense.MAX;
        Rational[] cost = new Rational[cols];
        java.util.Arrays.fill(cost, Rational.ZERO);
        Rational[] obj = model.objective();
        for (int j = 0; j < n; j++) {
            cost[j] = max ? obj[j] : obj[j].negate();
        }
        int entering = iterate(cost);
        Rational[] x = basicSolution();
        if (entering >= 0) {
            return new Solution.Unbounded(x, ray(entering));
        }
        Rational[] w = duals(cost);
        Rational[] y = new Rational[m];
        for (int i = 0; i < m; i++) {
            Rational v = w[i].mul(Rational.of(scale[i]));
            y[i] = max ? v : v.negate();
        }
        return new Solution.Optimal(Rational.dot(obj, x), x, y);
    }

    /** Maximises {@code cost . z}. Returns -1 when optimal, or the entering column that proves unboundedness. */
    private int iterate(Rational[] cost) {
        while (true) {
            int enter = -1;
            for (int j = 0; j < firstArt && enter < 0; j++) {
                if (isBasic(j)) {
                    continue;
                }
                Rational r = cost[j];
                for (int i = 0; i < m; i++) {
                    if (active[i]) {
                        r = r.sub(cost[basis[i]].mul(t[i][j]));
                    }
                }
                if (r.signum() > 0) {
                    enter = j; // Bland: smallest improving index
                }
            }
            if (enter < 0) {
                return -1;
            }
            int leave = -1;
            Rational best = null;
            for (int i = 0; i < m; i++) {
                if (!active[i] || t[i][enter].signum() <= 0) {
                    continue;
                }
                Rational ratio = t[i][cols].div(t[i][enter]);
                int cmp = best == null ? -1 : ratio.compareTo(best);
                if (cmp < 0 || (cmp == 0 && basis[i] < basis[leave])) {
                    best = ratio;
                    leave = i;
                }
            }
            if (leave < 0) {
                return enter;
            }
            pivot(leave, enter);
        }
    }

    private boolean isBasic(int j) {
        for (int i = 0; i < m; i++) {
            if (active[i] && basis[i] == j) {
                return true;
            }
        }
        return false;
    }

    private void pivot(int r, int c) {
        Rational inv = Rational.ONE.div(t[r][c]);
        for (int j = 0; j <= cols; j++) {
            t[r][j] = t[r][j].mul(inv);
        }
        for (int i = 0; i < m; i++) {
            if (i != r && active[i] && !t[i][c].isZero()) {
                Rational f = t[i][c];
                for (int j = 0; j <= cols; j++) {
                    t[i][j] = t[i][j].sub(f.mul(t[r][j]));
                }
            }
        }
        basis[r] = c;
    }

    /** After a zero-valued phase 1: pivot artificials out, or drop rows that are linearly dependent. */
    private void driveOutArtificials() {
        for (int i = 0; i < m; i++) {
            if (!active[i] || basis[i] < firstArt) {
                continue;
            }
            int col = -1;
            for (int j = 0; j < firstArt && col < 0; j++) {
                if (!t[i][j].isZero()) {
                    col = j;
                }
            }
            if (col < 0) {
                active[i] = false;
            } else {
                pivot(i, col);
            }
        }
    }

    private Rational[] basicSolution() {
        Rational[] x = new Rational[n];
        java.util.Arrays.fill(x, Rational.ZERO);
        for (int i = 0; i < m; i++) {
            if (active[i] && basis[i] < n) {
                x[basis[i]] = t[i][cols];
            }
        }
        return x;
    }

    private Rational[] ray(int entering) {
        Rational[] d = new Rational[n];
        java.util.Arrays.fill(d, Rational.ZERO);
        if (entering < n) {
            d[entering] = Rational.ONE;
        }
        for (int i = 0; i < m; i++) {
            if (active[i] && basis[i] < n) {
                d[basis[i]] = t[i][entering].negate();
            }
        }
        return d;
    }

    /** Solves {@code B^T w = c_B} over the active rows, using the original (unpivoted) columns. */
    private Rational[] duals(Rational[] cost) {
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            if (active[i]) {
                rows.add(i);
            }
        }
        int k = rows.size();
        Rational[][] bt = new Rational[k][k];
        Rational[] cb = new Rational[k];
        for (int p = 0; p < k; p++) {
            int col = basis[rows.get(p)];
            cb[p] = cost[col];
            for (int q = 0; q < k; q++) {
                bt[p][q] = a0[rows.get(q)][col];
            }
        }
        Rational[] sol = LinearSolve.solveSquare(bt, cb);
        if (sol == null) {
            throw new IllegalStateException("basis matrix is singular");
        }
        Rational[] w = new Rational[m];
        java.util.Arrays.fill(w, Rational.ZERO);
        for (int p = 0; p < k; p++) {
            w[rows.get(p)] = sol[p];
        }
        return w;
    }
}
