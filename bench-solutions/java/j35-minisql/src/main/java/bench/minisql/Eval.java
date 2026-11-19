package bench.minisql;

import java.util.List;

/** Expression evaluation with SQL three-valued logic. */
final class Eval {
    private Eval() { }

    /**
     * Evaluates e. Either row is the current row, or group is the row set of an aggregate group
     * (column references outside aggregate arguments then read the group's first row).
     */
    static Object eval(Expr e, Object[] row, List<Object[]> group) {
        switch (e) {
            case Expr.Lit l -> {
                return l.value();
            }
            case Expr.Col c -> {
                if (c.idx < 0) {
                    throw new SqlException("column '" + c.name + "' is not allowed here");
                }
                if (row != null) {
                    return row[c.idx];
                }
                if (group == null || group.isEmpty()) {
                    throw new SqlException("column '" + c.name + "' is not grouped");
                }
                return group.get(0)[c.idx];
            }
            case Expr.Neg n -> {
                Object v = eval(n.e(), row, group);
                if (v == null) {
                    return null;
                }
                if (v instanceof Long x) {
                    return -x;
                }
                throw new SqlException("unary minus needs INT");
            }
            case Expr.Not n -> {
                Object v = eval(n.e(), row, group);
                if (v == null) {
                    return null;
                }
                if (v instanceof Boolean b) {
                    return !b;
                }
                throw new SqlException("NOT needs a boolean");
            }
            case Expr.IsNull n -> {
                Object v = eval(n.e(), row, group);
                return (v == null) != n.negated();
            }
            case Expr.Agg a -> {
                return agg(a, group);
            }
            case Expr.Bin b -> {
                Object l = eval(b.l(), row, group);
                Object r = eval(b.r(), row, group);
                return bin(b.op(), l, r);
            }
        }
    }

    private static Boolean bool(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        throw new SqlException("boolean expected");
    }

    private static Object bin(String op, Object l, Object r) {
        switch (op) {
            case "AND" -> {
                Boolean a = bool(l);
                Boolean b = bool(r);
                if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) {
                    return Boolean.FALSE;
                }
                return a == null || b == null ? null : Boolean.TRUE;
            }
            case "OR" -> {
                Boolean a = bool(l);
                Boolean b = bool(r);
                if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) {
                    return Boolean.TRUE;
                }
                return a == null || b == null ? null : Boolean.FALSE;
            }
            case "||" -> {
                if ((l != null && !(l instanceof String)) || (r != null && !(r instanceof String))) {
                    throw new SqlException("|| needs TEXT");
                }
                return l == null || r == null ? null : (String) l + (String) r;
            }
            case "+", "-", "*", "/", "%" -> {
                if ((l != null && !(l instanceof Long)) || (r != null && !(r instanceof Long))) {
                    throw new SqlException("arithmetic needs INT");
                }
                if (l == null || r == null) {
                    return null;
                }
                long x = (Long) l;
                long y = (Long) r;
                switch (op) {
                    case "+" -> {
                        return x + y;
                    }
                    case "-" -> {
                        return x - y;
                    }
                    case "*" -> {
                        return x * y;
                    }
                    default -> {
                        if (y == 0) {
                            throw new SqlException("division by zero");
                        }
                        return op.equals("/") ? x / y : x % y;
                    }
                }
            }
            default -> {
                if (l == null || r == null) {
                    if (l != null) {
                        sameKind(l, l);
                    }
                    if (r != null) {
                        sameKind(r, r);
                    }
                    return null;
                }
                int c = compare(l, r);
                return switch (op) {
                    case "=" -> c == 0;
                    case "<>" -> c != 0;
                    case "<" -> c < 0;
                    case "<=" -> c <= 0;
                    case ">" -> c > 0;
                    default -> c >= 0;
                };
            }
        }
    }

    private static void sameKind(Object a, Object b) {
        boolean ok = (a instanceof Long && b instanceof Long) || (a instanceof String && b instanceof String)
            || (a instanceof Boolean && b instanceof Boolean);
        if (!ok) {
            throw new SqlException("cannot compare these types");
        }
    }

    /** Compares two non-null values of the same type; throws on a type mismatch. */
    static int compare(Object a, Object b) {
        sameKind(a, b);
        if (a instanceof Long x) {
            return Long.compare(x, (Long) b);
        }
        if (a instanceof String x) {
            return x.compareTo((String) b);
        }
        return Boolean.compare((Boolean) a, (Boolean) b);
    }

    private static Object agg(Expr.Agg a, List<Object[]> group) {
        if (group == null) {
            throw new SqlException("aggregate not allowed here");
        }
        if (a.arg() == null) {
            return (long) group.size();
        }
        long count = 0;
        long sum = 0;
        Object best = null;
        for (Object[] r : group) {
            Object v = eval(a.arg(), r, null);
            if (v == null) {
                continue;
            }
            count++;
            switch (a.fn()) {
                case "SUM" -> {
                    if (!(v instanceof Long x)) {
                        throw new SqlException("SUM needs INT");
                    }
                    sum += x;
                }
                case "MIN", "MAX" -> {
                    if (!(v instanceof Long) && !(v instanceof String)) {
                        throw new SqlException(a.fn() + " needs INT or TEXT");
                    }
                    if (best == null) {
                        best = v;
                    } else {
                        int c = compare(v, best);
                        if (a.fn().equals("MIN") ? c < 0 : c > 0) {
                            best = v;
                        }
                    }
                }
                default -> { }
            }
        }
        return switch (a.fn()) {
            case "COUNT" -> count;
            case "SUM" -> count == 0 ? null : (Object) sum;
            default -> best;
        };
    }
}
