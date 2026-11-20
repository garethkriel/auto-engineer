package ae.minisql;

import ae.minisql.Ast.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Expression binding (static checks) and evaluation with SQL three-valued logic. */
final class Eval {
    private Eval() {}

    // ---- static analysis -------------------------------------------------------------------------------------------

    /** Returns the expression with every column fully qualified; throws on unknown/ambiguous columns and nested aggregates. */
    static Expr bind(Expr e, Scope scope, boolean allowAgg, String clause) {
        return switch (e) {
            case Lit l -> l;
            case Col c -> {
                int i = scope.resolve(c);
                yield new Col(scope.entries().get(i).alias(), c.name());
            }
            case Un u -> new Un(u.op(), bind(u.e(), scope, allowAgg, clause));
            case Bin b -> new Bin(b.op(), bind(b.l(), scope, allowAgg, clause), bind(b.r(), scope, allowAgg, clause));
            case IsNull n -> new IsNull(bind(n.e(), scope, allowAgg, clause), n.negated());
            case In in -> {
                List<Expr> items = new ArrayList<>();
                for (Expr x : in.items()) items.add(bind(x, scope, allowAgg, clause));
                yield new In(bind(in.e(), scope, allowAgg, clause), items, in.negated());
            }
            case Like k -> new Like(bind(k.e(), scope, allowAgg, clause), bind(k.pattern(), scope, allowAgg, clause), k.negated());
            case Agg a -> {
                if (!allowAgg) throw new SqlException("aggregate not allowed in " + clause);
                if (a.arg() != null) {
                    if (containsAgg(a.arg())) throw new SqlException("nested aggregate " + a.fn().toUpperCase(java.util.Locale.ROOT));
                    yield new Agg(a.fn(), bind(a.arg(), scope, false, "aggregate argument"), a.distinct());
                }
                yield a;
            }
        };
    }

    static boolean containsAgg(Expr e) {
        return switch (e) {
            case Lit l -> false;
            case Col c -> false;
            case Un u -> containsAgg(u.e());
            case Bin b -> containsAgg(b.l()) || containsAgg(b.r());
            case IsNull n -> containsAgg(n.e());
            case In in -> containsAgg(in.e()) || in.items().stream().anyMatch(Eval::containsAgg);
            case Like k -> containsAgg(k.e()) || containsAgg(k.pattern());
            case Agg a -> true;
        };
    }

    /** Collects the table aliases of every column an expression references (join-key and pushdown analysis). */
    static void aliases(Expr e, java.util.Set<String> out) {
        switch (e) {
            case Lit l -> { }
            case Col c -> out.add(c.table());
            case Un u -> aliases(u.e(), out);
            case Bin b -> { aliases(b.l(), out); aliases(b.r(), out); }
            case IsNull n -> aliases(n.e(), out);
            case In in -> { aliases(in.e(), out); in.items().forEach(x -> aliases(x, out)); }
            case Like k -> { aliases(k.e(), out); aliases(k.pattern(), out); }
            case Agg a -> { if (a.arg() != null) aliases(a.arg(), out); }
        }
    }

    /** A grouped expression may only use group-by expressions, literals and aggregates. Inputs must be bound. */
    static void checkGrouped(Expr e, List<Expr> groupBy) {
        if (groupBy.contains(e)) return;
        switch (e) {
            case Lit l -> { }
            case Agg a -> { }
            case Col c -> throw new SqlException("column " + c + " must appear in GROUP BY or inside an aggregate");
            case Un u -> checkGrouped(u.e(), groupBy);
            case Bin b -> { checkGrouped(b.l(), groupBy); checkGrouped(b.r(), groupBy); }
            case IsNull n -> checkGrouped(n.e(), groupBy);
            case In in -> { checkGrouped(in.e(), groupBy); in.items().forEach(x -> checkGrouped(x, groupBy)); }
            case Like k -> { checkGrouped(k.e(), groupBy); checkGrouped(k.pattern(), groupBy); }
        }
    }

    // ---- evaluation ------------------------------------------------------------------------------------------------

    /** Evaluates {@code e} over {@code row}; {@code aggs} supplies aggregate values (null when none are allowed). */
    static Object eval(Expr e, Scope scope, Object[] row, Function<Agg, Object> aggs) {
        switch (e) {
            case Lit l -> { return l.value(); }
            case Col c -> { return row[scope.resolve(c)]; }
            case Agg a -> { return aggs.apply(a); }
            case Un u -> {
                Object v = eval(u.e(), scope, row, aggs);
                if (v == null) return null;
                if (u.op().equals("NOT")) return !bool(v, "NOT");
                try {
                    return Math.negateExact(num(v, "-"));
                } catch (ArithmeticException ex) {
                    throw new SqlException("integer overflow");
                }
            }
            case IsNull n -> {
                boolean isNull = eval(n.e(), scope, row, aggs) == null;
                return isNull != n.negated();
            }
            case Like k -> {
                Object v = eval(k.e(), scope, row, aggs), p = eval(k.pattern(), scope, row, aggs);
                if (v == null || p == null) return null;
                if (!(v instanceof String s) || !(p instanceof String ps)) throw new SqlException("type mismatch: LIKE needs TEXT");
                return LikeMatcher.matches(s, ps) != k.negated();
            }
            case In in -> {
                Object v = eval(in.e(), scope, row, aggs);
                boolean sawNull = v == null;
                boolean hit = false;
                for (Expr item : in.items()) {
                    Object w = eval(item, scope, row, aggs);
                    if (v == null || w == null) { sawNull = true; continue; }
                    if (compare(v, w) == 0) hit = true;
                }
                if (hit) return !in.negated();
                return sawNull ? null : (Object) in.negated();
            }
            case Bin b -> { return binary(b, scope, row, aggs); }
        }
    }

    private static Object binary(Bin b, Scope scope, Object[] row, Function<Agg, Object> aggs) {
        // both operands are always evaluated (no short-circuit) so error behaviour does not depend on operand order
        Object l = eval(b.l(), scope, row, aggs), r = eval(b.r(), scope, row, aggs);
        switch (b.op()) {
            case "AND" -> {
                Boolean x = l == null ? null : bool(l, "AND"), y = r == null ? null : bool(r, "AND");
                if (Boolean.FALSE.equals(x) || Boolean.FALSE.equals(y)) return false;
                return x == null || y == null ? null : (Object) true;
            }
            case "OR" -> {
                Boolean x = l == null ? null : bool(l, "OR"), y = r == null ? null : bool(r, "OR");
                if (Boolean.TRUE.equals(x) || Boolean.TRUE.equals(y)) return true;
                return x == null || y == null ? null : (Object) false;
            }
            default -> { }
        }
        if (l == null || r == null) {
            return null; // NULL operand: result is NULL (a type error on the other operand is not reported)
        }
        try {
            switch (b.op()) {
                case "+": return Math.addExact(num(l, "+"), num(r, "+"));
                case "-": return Math.subtractExact(num(l, "-"), num(r, "-"));
                case "*": return Math.multiplyExact(num(l, "*"), num(r, "*"));
                case "/": {
                    long d = num(r, "/");
                    if (d == 0) throw new SqlException("division by zero");
                    return Math.divideExact(num(l, "/"), d);
                }
                case "%": {
                    long d = num(r, "%");
                    if (d == 0) throw new SqlException("division by zero");
                    return num(l, "%") % d;
                }
                case "||":
                    if (l instanceof String x && r instanceof String y) return x + y;
                    throw new SqlException("type mismatch: || needs TEXT");
                case "=": return compare(l, r) == 0;
                case "<>": return compare(l, r) != 0;
                case "<": return compare(l, r) < 0;
                case "<=": return compare(l, r) <= 0;
                case ">": return compare(l, r) > 0;
                case ">=": return compare(l, r) >= 0;
                default: throw new IllegalStateException("operator " + b.op());
            }
        } catch (ArithmeticException ex) {
            throw new SqlException("integer overflow");
        }
    }

    private static long num(Object v, String op) {
        if (v instanceof Long n) return n;
        throw new SqlException("type mismatch: operator " + op + " needs INT");
    }

    private static boolean bool(Object v, String op) {
        if (v instanceof Boolean b) return b;
        throw new SqlException("type mismatch: operator " + op + " needs BOOL");
    }

    /** Total order for non-null values of the same type; different types are an error. */
    @SuppressWarnings("unchecked")
    static int compare(Object a, Object b) {
        if (a.getClass() != b.getClass()) throw new SqlException("type mismatch: cannot compare " + typeName(a) + " with " + typeName(b));
        return ((Comparable<Object>) a).compareTo(b);
    }

    static String typeName(Object v) {
        return v instanceof Long ? "INT" : v instanceof String ? "TEXT" : "BOOL";
    }

    /** WHERE/ON/HAVING keep a row only when the condition is TRUE. */
    static boolean truth(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw new SqlException("type mismatch: predicate must be BOOL, got " + typeName(v));
    }
}
