package ae.minisql;

import ae.minisql.Ast.*;
import java.util.List;

/** Expression evaluator with SQL three-valued logic (null = NULL / unknown). */
final class Eval {
    /** rows is the group (one row for non-aggregate queries); aggregates are allowed only when aggOk. */
    record Ctx(Schema schema, List<Object[]> rows, boolean aggOk) {
        Ctx single(Object[] row) {
            return new Ctx(schema, java.util.Collections.singletonList(row), false);
        }
    }

    private Eval() {}

    static Object eval(Expr e, Ctx c) {
        return switch (e) {
            case Lit l -> l.value();
            case Col col -> {
                int idx = c.schema().find(col.qualifier(), col.name(), col.pos());
                if (c.rows().isEmpty()) {
                    throw new SqlException("column " + col.name() + " has no row here", col.pos());
                }
                yield c.rows().get(0)[idx];
            }
            case Unary u -> unary(u, c);
            case Binary b -> binary(b, c);
            case IsNull n -> (eval(n.operand(), c) == null) != n.negated();
            case Like k -> like(k, c);
            case In i -> in(i, c);
            case Between b -> between(b, c);
            case Call call -> call(call, c);
        };
    }

    static boolean isTrue(Object v, String what, int pos) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new SqlException(what + " must be a boolean expression", pos);
    }

    private static Boolean bool(Object v, String op, int pos) {
        if (v == null || v instanceof Boolean) {
            return (Boolean) v;
        }
        throw new SqlException(op + " needs boolean operands", pos);
    }

    private static Object unary(Unary u, Ctx c) {
        Object v = eval(u.operand(), c);
        if (u.op().equals("NOT")) {
            Boolean b = bool(v, "NOT", u.pos());
            if (b == null) {
                return null;
            }
            return !b;
        }
        if (v == null) {
            return null;
        }
        if (v instanceof Long l) {
            return Math.negateExact(l);
        }
        if (v instanceof Double d) {
            return -d;
        }
        throw new SqlException("unary - needs a number", u.pos());
    }

    private static Object binary(Binary b, Ctx c) {
        String op = b.op();
        if (op.equals("AND") || op.equals("OR")) {
            boolean and = op.equals("AND");
            Boolean x = bool(eval(b.left(), c), op, b.pos());
            if (x != null && x != and) {
                return x; // FALSE AND ... = FALSE, TRUE OR ... = TRUE
            }
            Boolean y = bool(eval(b.right(), c), op, b.pos());
            if (y != null && y != and) {
                return y;
            }
            if (x == null || y == null) {
                return null;
            }
            return and;
        }
        Object l = eval(b.left(), c);
        Object r = eval(b.right(), c);
        if (l == null || r == null) {
            return null;
        }
        switch (op) {
            case "=": return compare(l, r, b.pos()) == 0;
            case "!=":
            case "<>": return compare(l, r, b.pos()) != 0;
            case "<": return compare(l, r, b.pos()) < 0;
            case "<=": return compare(l, r, b.pos()) <= 0;
            case ">": return compare(l, r, b.pos()) > 0;
            case ">=": return compare(l, r, b.pos()) >= 0;
            case "||":
                if (l instanceof String s && r instanceof String t) {
                    return s + t;
                }
                throw new SqlException("|| needs text operands", b.pos());
            default: return arithmetic(op, l, r, b.pos());
        }
    }

    private static Object arithmetic(String op, Object l, Object r, int pos) {
        if (!(l instanceof Number) || !(r instanceof Number)) {
            throw new SqlException("operator " + op + " needs numbers", pos);
        }
        if (l instanceof Long x && r instanceof Long y) {
            switch (op) {
                case "+": return Math.addExact(x, y);
                case "-": return Math.subtractExact(x, y);
                case "*": return Math.multiplyExact(x, y);
                default:
                    if (y == 0) {
                        throw new SqlException("division by zero", pos);
                    }
                    if (x == Long.MIN_VALUE && y == -1) {
                        throw new ArithmeticException("integer overflow");
                    }
                    return op.equals("/") ? x / y : x % y;
            }
        }
        double x = ((Number) l).doubleValue();
        double y = ((Number) r).doubleValue();
        switch (op) {
            case "+": return x + y;
            case "-": return x - y;
            case "*": return x * y;
            default:
                if (y == 0) {
                    throw new SqlException("division by zero", pos);
                }
                return op.equals("/") ? x / y : x % y;
        }
    }

    /** Total order on two non-null values of compatible types; mixed types are an error. */
    static int compare(Object a, Object b, int pos) {
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        throw new SqlException("cannot compare " + typeName(a) + " with " + typeName(b), pos);
    }

    static String typeName(Object v) {
        if (v instanceof Long) {
            return "INT";
        }
        if (v instanceof Double) {
            return "REAL";
        }
        if (v instanceof String) {
            return "TEXT";
        }
        return "BOOL";
    }

    private static Object like(Like k, Ctx c) {
        Object v = eval(k.operand(), c);
        Object p = eval(k.pattern(), c);
        if (v == null || p == null) {
            return null;
        }
        if (!(v instanceof String s) || !(p instanceof String pat)) {
            throw new SqlException("LIKE needs text operands", -1);
        }
        return matches(s, pat) != k.negated();
    }

    /** Iterative wildcard match: '%' any run, '_' one char. */
    static boolean matches(String s, String p) {
        int si = 0;
        int pi = 0;
        int star = -1;
        int mark = 0;
        while (si < s.length()) {
            if (pi < p.length() && p.charAt(pi) == '%') {
                star = pi++;
                mark = si;
            } else if (pi < p.length() && (p.charAt(pi) == '_' || p.charAt(pi) == s.charAt(si))) {
                pi++;
                si++;
            } else if (star >= 0) {
                pi = star + 1;
                si = ++mark;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '%') {
            pi++;
        }
        return pi == p.length();
    }

    private static Object in(In in, Ctx c) {
        Object v = eval(in.operand(), c);
        if (v == null) {
            return null;
        }
        boolean sawNull = false;
        for (Expr item : in.items()) {
            Object x = eval(item, c);
            if (x == null) {
                sawNull = true;
            } else if (compare(v, x, -1) == 0) {
                return !in.negated();
            }
        }
        if (sawNull) {
            return null;
        }
        return in.negated();
    }

    private static Object between(Between b, Ctx c) {
        Object v = eval(b.operand(), c);
        Object lo = eval(b.low(), c);
        Object hi = eval(b.high(), c);
        Boolean ge = null;
        if (v != null && lo != null) {
            ge = compare(v, lo, -1) >= 0;
        }
        Boolean le = null;
        if (v != null && hi != null) {
            le = compare(v, hi, -1) <= 0;
        }
        Boolean result;
        if (Boolean.FALSE.equals(ge) || Boolean.FALSE.equals(le)) {
            result = false;
        } else if (ge == null || le == null) {
            result = null;
        } else {
            result = true;
        }
        if (result == null) {
            return null;
        }
        return result != b.negated();
    }

    private static Object call(Call call, Ctx c) {
        if (Ast.isAggregateName(call.name())) {
            return aggregate(call, c);
        }
        switch (call.name()) {
            case "COALESCE":
                for (Expr a : call.args()) {
                    Object v = eval(a, c);
                    if (v != null) {
                        return v;
                    }
                }
                return null;
            case "UPPER":
            case "LOWER":
            case "LENGTH": {
                Object v = eval(call.args().get(0), c);
                if (v == null) {
                    return null;
                }
                if (!(v instanceof String s)) {
                    throw new SqlException(call.name() + " needs text", call.pos());
                }
                if (call.name().equals("LENGTH")) {
                    return (long) s.length();
                }
                return call.name().equals("UPPER") ? s.toUpperCase(java.util.Locale.ROOT) : s.toLowerCase(java.util.Locale.ROOT);
            }
            default: { // ABS
                Object v = eval(call.args().get(0), c);
                if (v == null) {
                    return null;
                }
                if (v instanceof Long l) {
                    return Math.absExact(l);
                }
                if (v instanceof Double d) {
                    return Math.abs(d);
                }
                throw new SqlException("ABS needs a number", call.pos());
            }
        }
    }

    private static Object aggregate(Call call, Ctx c) {
        if (!c.aggOk()) {
            throw new SqlException("aggregate " + call.name() + " is not allowed here", call.pos());
        }
        if (call.star()) {
            return (long) c.rows().size();
        }
        Expr arg = call.args().get(0);
        long count = 0;
        long sum = 0;
        Object best = null;
        for (Object[] row : c.rows()) {
            Object v = eval(arg, c.single(row));
            if (v == null) {
                continue;
            }
            count++;
            switch (call.name()) {
                case "SUM":
                case "AVG":
                    if (!(v instanceof Long l)) {
                        throw new SqlException(call.name() + " needs INT values", call.pos());
                    }
                    sum = Math.addExact(sum, l);
                    break;
                case "MIN":
                    if (best == null || compare(v, best, call.pos()) < 0) {
                        best = v;
                    }
                    break;
                case "MAX":
                    if (best == null || compare(v, best, call.pos()) > 0) {
                        best = v;
                    }
                    break;
                default:
                    break;
            }
        }
        switch (call.name()) {
            case "COUNT": return count;
            case "SUM": return count == 0 ? null : (Object) sum;
            case "AVG": return count == 0 ? null : (Object) ((double) sum / count);
            default: return best;
        }
    }
}
