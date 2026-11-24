package bench.minisql;

import bench.minisql.Sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A tiny in-memory SQL database. */
public final class Database {
    public record Result(List<String> columns, List<List<Object>> rows, int affected) { }

    private static final class Table {
        final List<String> cols;
        final boolean[] isInt;
        final Map<String, Integer> idx = new HashMap<>();
        final ArrayList<Object[]> rows = new ArrayList<>();

        Table(List<String> cols, boolean[] isInt) {
            this.cols = cols;
            this.isInt = isInt;
            for (int i = 0; i < cols.size(); i++) idx.put(cols.get(i), i);
        }
    }

    private final Map<String, Table> tables = new HashMap<>();

    public Database() { }

    public Result execute(String sql) {
        if (sql == null) throw new SqlException("null statement");
        try {
            Stmt s = new Parser(Sql.lex(sql)).parseStatement();
            return run(s);
        } catch (SqlException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SqlException("internal error: " + e);
        } catch (StackOverflowError e) {
            throw new SqlException("statement too deeply nested");
        }
    }

    private static Result done(int affected) {
        return new Result(List.of(), List.of(), affected);
    }

    private Result run(Stmt s) {
        if (s instanceof Create c) return create(c);
        if (s instanceof Drop d) {
            if (tables.remove(d.table()) == null) throw new SqlException("unknown table " + d.table());
            return done(0);
        }
        if (s instanceof Insert i) return insert(i);
        if (s instanceof Update u) return update(u);
        if (s instanceof Delete d) return delete(d);
        return select((Select) s);
    }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw new SqlException("unknown table " + name);
        return t;
    }

    // ------------------------------------------------------------ statements
    private Result create(Create c) {
        if (tables.containsKey(c.table())) throw new SqlException("table exists: " + c.table());
        if (new HashSet<>(c.cols()).size() != c.cols().size()) throw new SqlException("duplicate column name");
        boolean[] ints = new boolean[c.cols().size()];
        for (int i = 0; i < ints.length; i++) ints[i] = c.isInt().get(i);
        tables.put(c.table(), new Table(c.cols(), ints));
        return done(0);
    }

    private static Object store(Table t, int col, Object v) {
        if (v == null) return null;
        if (t.isInt[col] && v instanceof Long) return v;
        if (!t.isInt[col] && v instanceof String) return v;
        throw new SqlException("type mismatch for column " + t.cols.get(col));
    }

    private Result insert(Insert in) {
        Table t = table(in.table());
        int n = t.cols.size();
        int[] target;
        if (in.cols() == null) {
            target = new int[n];
            for (int i = 0; i < n; i++) target[i] = i;
        } else {
            target = new int[in.cols().size()];
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < target.length; i++) {
                Integer ix = t.idx.get(in.cols().get(i));
                if (ix == null) throw new SqlException("unknown column " + in.cols().get(i));
                if (!seen.add(ix)) throw new SqlException("duplicate column " + in.cols().get(i));
                target[i] = ix;
            }
        }
        for (List<Expr> tuple : in.tuples()) {
            if (tuple.size() != target.length) throw new SqlException("value count mismatch");
            for (Expr e : tuple) check(e, null, false, false, null);
        }
        List<Object[]> fresh = new ArrayList<>(in.tuples().size());
        for (List<Expr> tuple : in.tuples()) {
            Object[] row = new Object[n];
            for (int i = 0; i < target.length; i++) {
                row[target[i]] = store(t, target[i], eval(tuple.get(i), null, null));
            }
            fresh.add(row);
        }
        t.rows.addAll(fresh);
        return done(fresh.size());
    }

    private Result update(Update u) {
        Table t = table(u.table());
        int[] target = new int[u.cols().size()];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < target.length; i++) {
            Integer ix = t.idx.get(u.cols().get(i));
            if (ix == null) throw new SqlException("unknown column " + u.cols().get(i));
            if (!seen.add(ix)) throw new SqlException("column set twice: " + u.cols().get(i));
            target[i] = ix;
        }
        for (Expr e : u.exprs()) check(e, t, false, false, null);
        if (u.where() != null) check(u.where(), t, false, false, null);
        List<Integer> positions = new ArrayList<>();
        List<Object[]> replacements = new ArrayList<>();
        for (int r = 0; r < t.rows.size(); r++) {
            Object[] row = t.rows.get(r);
            if (u.where() != null && !cond(u.where(), row, null)) continue;
            Object[] nr = row.clone();
            for (int i = 0; i < target.length; i++) {
                nr[target[i]] = store(t, target[i], eval(u.exprs().get(i), row, null));
            }
            positions.add(r);
            replacements.add(nr);
        }
        for (int i = 0; i < positions.size(); i++) t.rows.set(positions.get(i), replacements.get(i));
        return done(positions.size());
    }

    private Result delete(Delete d) {
        Table t = table(d.table());
        if (d.where() == null) {
            int n = t.rows.size();
            t.rows.clear();
            return done(n);
        }
        check(d.where(), t, false, false, null);
        ArrayList<Object[]> kept = new ArrayList<>();
        for (Object[] row : t.rows) if (!cond(d.where(), row, null)) kept.add(row);
        int n = t.rows.size() - kept.size();
        t.rows.clear();
        t.rows.addAll(kept);
        return done(n);
    }

    // ---------------------------------------------------------------- select
    private static final class Entry {
        final Object[] out;
        Object[] keys;

        Entry(Object[] out) {
            this.out = out;
        }
    }

    private Result select(Select s) {
        Table t = table(s.table());
        List<Expr> items = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (s.star()) {
            for (int i = 0; i < t.cols.size(); i++) {
                Col c = new Col(t.cols.get(i));
                c.idx = i;
                items.add(c);
                names.add(t.cols.get(i));
            }
        } else {
            items.addAll(s.items());
            for (int i = 0; i < items.size(); i++) {
                String a = s.aliases().get(i);
                if (a != null) names.add(a);
                else if (items.get(i) instanceof Col c) names.add(c.name);
                else names.add("col" + (i + 1));
            }
        }
        // order-by resolution
        int nOrder = s.order().size();
        int[] outIdx = new int[nOrder];
        for (int i = 0; i < nOrder; i++) {
            OrderItem o = s.order().get(i);
            outIdx[i] = -1;
            if (o.bareInt() != null) {
                long k = o.bareInt();
                if (k < 1 || k > names.size()) throw new SqlException("ORDER BY position out of range: " + k);
                outIdx[i] = (int) k - 1;
            } else if (o.bareName() != null) {
                outIdx[i] = names.indexOf(o.bareName());
            }
        }
        boolean aggregate = s.groupBy() != null || s.having() != null;
        if (!aggregate && !s.star()) {
            for (Expr e : items) if (hasAgg(e)) aggregate = true;
        }
        if (!aggregate) {
            for (int i = 0; i < nOrder; i++) if (outIdx[i] < 0 && hasAgg(s.order().get(i).expr())) aggregate = true;
        }
        if (aggregate && s.star()) throw new SqlException("* is not allowed in an aggregate query");

        Set<Integer> grouped = null;
        int[] groupIdx = new int[0];
        if (aggregate) {
            grouped = new HashSet<>();
            if (s.groupBy() != null) {
                groupIdx = new int[s.groupBy().size()];
                for (int i = 0; i < groupIdx.length; i++) {
                    Integer ix = t.idx.get(s.groupBy().get(i));
                    if (ix == null) throw new SqlException("unknown column " + s.groupBy().get(i));
                    groupIdx[i] = ix;
                    grouped.add(ix);
                }
            }
        }
        if (s.where() != null) check(s.where(), t, false, false, null);
        for (Expr e : items) check(e, t, aggregate, false, grouped);
        if (s.having() != null) check(s.having(), t, true, false, grouped);
        for (int i = 0; i < nOrder; i++) {
            if (outIdx[i] < 0) check(s.order().get(i).expr(), t, aggregate, false, grouped);
        }

        // filter
        List<Object[]> rows;
        if (s.where() == null) {
            rows = t.rows;
        } else {
            rows = new ArrayList<>();
            for (Object[] r : t.rows) if (cond(s.where(), r, null)) rows.add(r);
        }

        int w = items.size();
        List<Entry> entries = new ArrayList<>();
        if (!aggregate) {
            for (Object[] r : rows) {
                Object[] out = new Object[w];
                for (int i = 0; i < w; i++) out[i] = eval(items.get(i), r, null);
                Entry en = new Entry(out);
                en.keys = orderKeys(s, outIdx, out, r, null);
                entries.add(en);
            }
        } else {
            Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
            if (groupIdx.length == 0) {
                groups.put(List.of(), new ArrayList<>(rows));
            } else {
                for (Object[] r : rows) {
                    Object[] key = new Object[groupIdx.length];
                    for (int i = 0; i < key.length; i++) key[i] = r[groupIdx[i]];
                    groups.computeIfAbsent(Arrays.asList(key), k -> new ArrayList<>()).add(r);
                }
            }
            for (List<Object[]> g : groups.values()) {
                Object[] rep = g.isEmpty() ? null : g.get(0);
                if (s.having() != null && !cond(s.having(), rep, g)) continue;
                Object[] out = new Object[w];
                for (int i = 0; i < w; i++) out[i] = eval(items.get(i), rep, g);
                Entry en = new Entry(out);
                en.keys = orderKeys(s, outIdx, out, rep, g);
                entries.add(en);
            }
        }

        if (nOrder > 0) {
            final boolean[] desc = new boolean[nOrder];
            for (int i = 0; i < nOrder; i++) desc[i] = s.order().get(i).desc();
            entries.sort((a, b) -> {
                for (int i = 0; i < desc.length; i++) {
                    int c = compareValues(a.keys[i], b.keys[i]);
                    if (c != 0) return desc[i] ? -c : c;
                }
                return 0;
            });
        }

        int from = 0;
        int to = entries.size();
        if (s.offset() != null) from = (int) Math.min(s.offset(), (long) entries.size());
        if (s.limit() != null) to = from + (int) Math.min(s.limit(), (long) (entries.size() - from));
        List<List<Object>> result = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            result.add(Collections.unmodifiableList(Arrays.asList(entries.get(i).out)));
        }
        return new Result(Collections.unmodifiableList(new ArrayList<>(names)),
                Collections.unmodifiableList(result), 0);
    }

    private static Object[] orderKeys(Select s, int[] outIdx, Object[] out, Object[] row, List<Object[]> group) {
        int n = outIdx.length;
        if (n == 0) return null;
        Object[] keys = new Object[n];
        for (int i = 0; i < n; i++) {
            keys[i] = outIdx[i] >= 0 ? out[outIdx[i]] : eval(s.order().get(i).expr(), row, group);
        }
        return keys;
    }

    // ------------------------------------------------------------ validation
    private static boolean hasAgg(Expr e) {
        if (e instanceof Agg) return true;
        if (e instanceof Neg n) return hasAgg(n.e());
        if (e instanceof Not n) return hasAgg(n.e());
        if (e instanceof IsNull n) return hasAgg(n.e());
        if (e instanceof Bin b) return hasAgg(b.l()) || hasAgg(b.r());
        return false;
    }

    /** Resolves columns and validates aggregate placement. t is null where columns are not allowed. */
    private static void check(Expr e, Table t, boolean allowAgg, boolean inAgg, Set<Integer> grouped) {
        if (e instanceof Col c) {
            if (t == null) throw new SqlException("column reference not allowed here: " + c.name);
            Integer ix = t.idx.get(c.name);
            if (ix == null) throw new SqlException("unknown column " + c.name);
            c.idx = ix;
            if (grouped != null && !inAgg && !grouped.contains(ix)) {
                throw new SqlException("column " + c.name + " must be grouped or aggregated");
            }
        } else if (e instanceof Agg a) {
            if (!allowAgg || inAgg) throw new SqlException("aggregate not allowed here");
            if (a.arg() != null) check(a.arg(), t, allowAgg, true, grouped);
        } else if (e instanceof Neg n) {
            check(n.e(), t, allowAgg, inAgg, grouped);
        } else if (e instanceof Not n) {
            check(n.e(), t, allowAgg, inAgg, grouped);
        } else if (e instanceof IsNull n) {
            check(n.e(), t, allowAgg, inAgg, grouped);
        } else if (e instanceof Bin b) {
            check(b.l(), t, allowAgg, inAgg, grouped);
            check(b.r(), t, allowAgg, inAgg, grouped);
        }
    }

    // ------------------------------------------------------------ evaluation
    private static boolean cond(Expr e, Object[] row, List<Object[]> group) {
        Object v = eval(e, row, group);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw new SqlException("condition must be boolean");
    }

    private static Object eval(Expr e, Object[] row, List<Object[]> group) {
        if (e instanceof Lit l) return l.v();
        if (e instanceof Col c) {
            if (row == null) throw new SqlException("no row for column " + c.name);
            return row[c.idx];
        }
        if (e instanceof Agg a) return aggregate(a, group);
        if (e instanceof IsNull n) {
            boolean isNull = eval(n.e(), row, group) == null;
            return n.negated() != isNull;
        }
        if (e instanceof Neg n) {
            Object v = eval(n.e(), row, group);
            if (v == null) return null;
            if (v instanceof Long x) return -x;
            throw new SqlException("unary minus needs INT");
        }
        if (e instanceof Not n) {
            Object v = eval(n.e(), row, group);
            if (v == null) return null;
            if (v instanceof Boolean b) return !b;
            throw new SqlException("NOT needs a boolean");
        }
        Bin b = (Bin) e;
        Object l = eval(b.l(), row, group);
        Object r = eval(b.r(), row, group);
        switch (b.op()) {
            case "AND", "OR": {
                if ((l != null && !(l instanceof Boolean)) || (r != null && !(r instanceof Boolean))) {
                    throw new SqlException(b.op() + " needs booleans");
                }
                boolean and = b.op().equals("AND");
                Boolean absorbing = and ? Boolean.FALSE : Boolean.TRUE;
                if (absorbing.equals(l) || absorbing.equals(r)) return absorbing;
                if (l == null || r == null) return null;
                return !absorbing;
            }
            case "+", "-", "*", "/", "%": {
                if ((l != null && !(l instanceof Long)) || (r != null && !(r instanceof Long))) {
                    throw new SqlException("arithmetic needs INT operands");
                }
                if (l == null || r == null) return null;
                long x = (Long) l;
                long y = (Long) r;
                switch (b.op()) {
                    case "+": return x + y;
                    case "-": return x - y;
                    case "*": return x * y;
                    case "/":
                        if (y == 0) throw new SqlException("division by zero");
                        return x / y;
                    default:
                        if (y == 0) throw new SqlException("division by zero");
                        return x % y;
                }
            }
            case "||": {
                if ((l != null && !(l instanceof String)) || (r != null && !(r instanceof String))) {
                    throw new SqlException("|| needs TEXT operands");
                }
                if (l == null || r == null) return null;
                return (String) l + (String) r;
            }
            default: {
                if (l == null || r == null) return null;
                int c;
                if (l instanceof Long x && r instanceof Long y) c = Long.compare(x, y);
                else if (l instanceof String x && r instanceof String y) c = x.compareTo(y);
                else if (l instanceof Boolean x && r instanceof Boolean y) c = Boolean.compare(x, y);
                else throw new SqlException("cannot compare values of different types");
                return switch (b.op()) {
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

    private static Object aggregate(Agg a, List<Object[]> group) {
        if (group == null) throw new SqlException("aggregate not allowed here");
        if (a.arg() == null) return (long) group.size();
        long count = 0;
        long sum = 0;
        Object best = null;
        for (Object[] r : group) {
            Object v = eval(a.arg(), r, null);
            if (v == null) continue;
            count++;
            switch (a.fn()) {
                case "SUM" -> {
                    if (!(v instanceof Long x)) throw new SqlException("SUM needs INT");
                    sum += x;
                }
                case "MIN", "MAX" -> {
                    if (!(v instanceof Long) && !(v instanceof String)) throw new SqlException(a.fn() + " needs INT or TEXT");
                    if (best == null) {
                        best = v;
                    } else {
                        int c = compareValues(v, best);
                        if (a.fn().equals("MIN") ? c < 0 : c > 0) best = v;
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

    private static int compareValues(Object a, Object b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        if (a instanceof Long x && b instanceof Long y) return Long.compare(x, y);
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return Boolean.compare(x, y);
        throw new SqlException("cannot compare values of different types");
    }
}
