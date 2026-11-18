package bench.minisql;

import java.util.List;

/** Expression tree nodes with direct evaluation. */
abstract class Expr {

    /** Evaluates against a row; {@code grp} is the group's rows for aggregate evaluation. */
    abstract Object eval(Object[] row, List<Object[]> grp);

    Expr[] kids() {
        return NONE;
    }

    private static final Expr[] NONE = new Expr[0];

    static SqlException err(String m) {
        return new SqlException(m);
    }

    static final class Lit extends Expr {
        final Object value;

        Lit(Object value) {
            this.value = value;
        }

        @Override
        Object eval(Object[] row, List<Object[]> grp) {
            return value;
        }
    }

    static final class Col extends Expr {
        final String name;
        int idx = -1;

        Col(String name) {
            this.name = name;
        }

        @Override
        Object eval(Object[] row, List<Object[]> grp) {
            if (idx < 0 || row == null) {
                throw err("column not available: " + name);
            }
            return row[idx];
        }
    }

    static final class Neg extends Expr {
        final Expr e;

        Neg(Expr e) {
            this.e = e;
        }

        @Override
        Expr[] kids() {
            return new Expr[] {e};
        }

        @Override
        Object eval(Object[] row, List<Object[]> grp) {
            Object v = e.eval(row, grp);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Long l)) {
                throw err("unary minus needs INT");
            }
            return -l;
        }
    }

    static final class Not extends Expr {
        final Expr e;

        Not(Expr e) {
            this.e = e;
        }

        @Override
        Expr[] kids() {
            return new Expr[] {e};
        }

        @Override
        Object eval(Object[] row, List<Object[]> grp) {
            Object v = e.eval(row, grp);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Boolean b)) {
                throw err("NOT needs a boolean");
            }
            return !b;
        }
    }

    static final class IsNull extends Expr {
        final Expr e;
        final boolean negated;

        IsNull(Expr e, boolean negated) {
            this.e = e;
            this.negated = negated;
        }

        @Override
        Expr[] kids() {
            return new Expr[] {e};
        }

        @Override
        Object eval(Object[] row, List<Object[]> grp) {
            return (e.eval(row, grp) == null) != negated;
        }
    }

    enum Op { ADD, SUB, MUL, DIV, MOD, CONCAT, EQ, NE, LT, LE, GT, GE, AND, OR }

    static final class Bin extends Expr {
        final Op op;
        final Expr l;
        final Expr r;

        Bin(Op op, Expr l, Expr r) {
            this.op = op;
            this.l = l;
            this.r = r;
        }

        @Override
        Expr[] kids() {
            return new Expr[] {l, r};
        }

        @Override
        Object eval(Object[] row, List<Object[]> grp) {
            Object a = l.eval(row, grp);
            Object b = r.eval(row, grp);
            switch (op) {
                case AND, OR:
                    return logic(a, b);
                case CONCAT:
                    if (a != null && !(a instanceof String)) {
                        throw err("|| needs TEXT");
                    }
                    if (b != null && !(b instanceof String)) {
                        throw err("|| needs TEXT");
                    }
                    if (a == null || b == null) {
                        return null;
                    }
                    return (String) a + (String) b;
                case EQ, NE, LT, LE, GT, GE:
                    return compare(a, b);
                default:
                    return arith(a, b);
            }
        }

        private Object logic(Object a, Object b) {
            if (a != null && !(a instanceof Boolean)) {
                throw err(op + " needs booleans");
            }
            if (b != null && !(b instanceof Boolean)) {
                throw err(op + " needs booleans");
            }
            if (op == Op.AND) {
                if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) {
                    return Boolean.FALSE;
                }
                if (a == null || b == null) {
                    return null;
                }
                return Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) {
                return Boolean.TRUE;
            }
            if (a == null || b == null) {
                return null;
            }
            return Boolean.FALSE;
        }

        private Object compare(Object a, Object b) {
            if (a == null || b == null) {
                return null;
            }
            int c;
            if (a instanceof Long x && b instanceof Long y) {
                c = Long.compare(x, y);
            } else if (a instanceof String x && b instanceof String y) {
                c = x.compareTo(y);
            } else if (a instanceof Boolean x && b instanceof Boolean y) {
                c = Boolean.compare(x, y);
            } else {
                throw err("cannot compare these types");
            }
            return switch (op) {
                case EQ -> c == 0;
                case NE -> c != 0;
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                default -> c >= 0;
            };
        }

        private Object arith(Object a, Object b) {
            if (a != null && !(a instanceof Long)) {
                throw err("arithmetic needs INT");
            }
            if (b != null && !(b instanceof Long)) {
                throw err("arithmetic needs INT");
            }
            if (a == null || b == null) {
                return null;
            }
            long x = (Long) a;
            long y = (Long) b;
            return switch (op) {
                case ADD -> x + y;
                case SUB -> x - y;
                case MUL -> x * y;
                case DIV -> {
                    if (y == 0) {
                        throw err("division by zero");
                    }
                    yield x / y;
                }
                default -> {
                    if (y == 0) {
                        throw err("division by zero");
                    }
                    yield x % y;
                }
            };
        }
    }

    static final class Agg extends Expr {
        final String fn; // COUNT, SUM, MIN, MAX
        final Expr arg; // null means COUNT(*)

        Agg(String fn, Expr arg) {
            this.fn = fn;
            this.arg = arg;
        }

        @Override
        Expr[] kids() {
            return arg == null ? new Expr[0] : new Expr[] {arg};
        }

        @Override
        Object eval(Object[] row, List<Object[]> grp) {
            if (grp == null) {
                throw err("aggregate not allowed here");
            }
            if (arg == null) {
                return (long) grp.size();
            }
            long count = 0;
            long sum = 0;
            Object best = null;
            for (Object[] g : grp) {
                Object v = arg.eval(g, null);
                if (v == null) {
                    continue;
                }
                count++;
                switch (fn) {
                    case "SUM":
                        if (!(v instanceof Long l)) {
                            throw err("SUM needs INT");
                        }
                        sum += l;
                        break;
                    case "MIN", "MAX":
                        if (!(v instanceof Long) && !(v instanceof String)) {
                            throw err(fn + " needs INT or TEXT");
                        }
                        if (best == null) {
                            best = v;
                        } else {
                            if (best.getClass() != v.getClass()) {
                                throw err(fn + " over mixed types");
                            }
                            @SuppressWarnings("unchecked")
                            int c = ((Comparable<Object>) v).compareTo(best);
                            if (fn.equals("MIN") ? c < 0 : c > 0) {
                                best = v;
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
            return switch (fn) {
                case "COUNT" -> (Object) count;
                case "SUM" -> count == 0 ? null : (Object) sum;
                default -> best;
            };
        }
    }

    static boolean containsAgg(Expr e) {
        if (e instanceof Agg) {
            return true;
        }
        for (Expr k : e.kids()) {
            if (containsAgg(k)) {
                return true;
            }
        }
        return false;
    }
}
