package ae.lp;

import java.util.ArrayList;
import java.util.List;

/**
 * Independent exact verifier. It uses only the model and the certificate, so a solver bug cannot
 * make a wrong answer pass. An empty result list means the certificate is valid.
 */
public final class CertificateChecker {
    private CertificateChecker() { }

    public static List<String> check(LpModel m, Solution s) {
        List<String> errs = new ArrayList<>();
        switch (s) {
            case Solution.Optimal o -> checkOptimal(m, o, errs);
            case Solution.Infeasible i -> checkInfeasible(m, i, errs);
            case Solution.Unbounded u -> checkUnbounded(m, u, errs);
        }
        return errs;
    }

    /** True when {@code x >= 0} satisfies every constraint. */
    public static boolean isFeasible(LpModel m, Rational[] x) {
        for (Rational v : x) {
            if (v.signum() < 0) {
                return false;
            }
        }
        for (LpModel.Constraint c : m.constraints()) {
            if (!holds(Rational.dot(c.coef(), x), c.op(), c.rhs())) {
                return false;
            }
        }
        return true;
    }

    private static boolean holds(Rational lhs, LpModel.Op op, Rational rhs) {
        int cmp = lhs.compareTo(rhs);
        return switch (op) {
            case LE -> cmp <= 0;
            case GE -> cmp >= 0;
            case EQ -> cmp == 0;
        };
    }

    /** Sign rule for a dual/Farkas entry of a row, given the problem sense. */
    private static boolean signOk(LpModel.Op op, Rational y, boolean maxStyle) {
        return switch (op) {
            case EQ -> true;
            case LE -> maxStyle ? y.signum() >= 0 : y.signum() <= 0;
            case GE -> maxStyle ? y.signum() <= 0 : y.signum() >= 0;
        };
    }

    private static Rational[] column(LpModel m, int j) {
        List<LpModel.Constraint> cs = m.constraints();
        Rational[] col = new Rational[cs.size()];
        for (int i = 0; i < col.length; i++) {
            col[i] = cs.get(i).coef()[j];
        }
        return col;
    }

    private static Rational[] rhs(LpModel m) {
        return m.constraints().stream().map(LpModel.Constraint::rhs).toArray(Rational[]::new);
    }

    private static void checkOptimal(LpModel m, Solution.Optimal o, List<String> errs) {
        int n = m.variables().size();
        int rows = m.constraints().size();
        if (o.x().length != n) {
            errs.add("primal length " + o.x().length + " != " + n);
            return;
        }
        if (o.duals().length != rows) {
            errs.add("dual length " + o.duals().length + " != " + rows);
            return;
        }
        for (int j = 0; j < n; j++) {
            if (o.x()[j].signum() < 0) {
                errs.add("negative primal variable " + m.variables().get(j));
            }
        }
        for (LpModel.Constraint c : m.constraints()) {
            if (!holds(Rational.dot(c.coef(), o.x()), c.op(), c.rhs())) {
                errs.add("primal constraint " + c.name() + " violated");
            }
        }
        Rational primal = Rational.dot(m.objective(), o.x());
        if (!primal.equals(o.value())) {
            errs.add("objective value " + o.value() + " != c.x = " + primal);
        }
        boolean max = m.sense() == LpModel.Sense.MAX;
        for (int i = 0; i < rows; i++) {
            if (!signOk(m.constraints().get(i).op(), o.duals()[i], max)) {
                errs.add("dual sign wrong for constraint " + m.constraints().get(i).name());
            }
        }
        for (int j = 0; j < n; j++) {
            int cmp = Rational.dot(o.duals(), column(m, j)).compareTo(m.objective()[j]);
            if (max ? cmp < 0 : cmp > 0) {
                errs.add("dual constraint violated for variable " + m.variables().get(j));
            }
        }
        Rational dual = Rational.dot(o.duals(), rhs(m));
        if (!dual.equals(primal)) {
            errs.add("dual objective " + dual + " != primal objective " + primal);
        }
    }

    private static void checkInfeasible(LpModel m, Solution.Infeasible f, List<String> errs) {
        int rows = m.constraints().size();
        if (f.farkas().length != rows) {
            errs.add("Farkas length " + f.farkas().length + " != " + rows);
            return;
        }
        for (int i = 0; i < rows; i++) {
            if (!signOk(m.constraints().get(i).op(), f.farkas()[i], true)) {
                errs.add("Farkas sign wrong for constraint " + m.constraints().get(i).name());
            }
        }
        for (int j = 0; j < m.variables().size(); j++) {
            if (Rational.dot(f.farkas(), column(m, j)).signum() < 0) {
                errs.add("Farkas: y^T A is negative for variable " + m.variables().get(j));
            }
        }
        if (Rational.dot(f.farkas(), rhs(m)).signum() >= 0) {
            errs.add("Farkas: y^T b is not negative, so nothing is proved infeasible");
        }
    }

    private static void checkUnbounded(LpModel m, Solution.Unbounded u, List<String> errs) {
        int n = m.variables().size();
        if (u.point().length != n || u.ray().length != n) {
            errs.add("point/ray length != " + n);
            return;
        }
        if (!isFeasible(m, u.point())) {
            errs.add("point is not feasible");
        }
        for (int j = 0; j < n; j++) {
            if (u.ray()[j].signum() < 0) {
                errs.add("ray has negative component for " + m.variables().get(j));
            }
        }
        for (LpModel.Constraint c : m.constraints()) {
            Rational ad = Rational.dot(c.coef(), u.ray());
            boolean ok = switch (c.op()) {
                case LE -> ad.signum() <= 0;
                case GE -> ad.signum() >= 0;
                case EQ -> ad.signum() == 0;
            };
            if (!ok) {
                errs.add("ray leaves the recession cone at constraint " + c.name());
            }
        }
        Rational cd = Rational.dot(m.objective(), u.ray());
        boolean improves = m.sense() == LpModel.Sense.MAX ? cd.signum() > 0 : cd.signum() < 0;
        if (!improves) {
            errs.add("objective does not improve along the ray (c.d = " + cd + ")");
        }
    }
}
