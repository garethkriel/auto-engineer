package ae.minisql;

import ae.minisql.Ast.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory SQL database. Not thread-safe. A statement either completes or leaves all state unchanged.
 * See {@code TASK.md} for the supported language and the semantic decisions.
 */
public final class Database {
    private Map<String, Table> tables = new LinkedHashMap<>();
    private Map<String, Table> snapshot; // non-null while a transaction is open
    private Planner planner = Planner.OPTIMIZED;
    private List<String> lastPlan = List.of();

    public void setPlanner(Planner p) {
        this.planner = java.util.Objects.requireNonNull(p);
    }

    /** Human-readable plan of the most recent SELECT (one line per step). */
    public List<String> lastPlan() {
        return lastPlan;
    }

    /** Executes exactly one statement (an optional trailing ';' is allowed). */
    public Result execute(String sql) {
        return run(Parser.parseOne(sql));
    }

    /** Executes a ';'-separated script and returns the last statement's result (empty result for an empty script). */
    public Result executeScript(String sql) {
        Result last = Result.count(0);
        for (Stmt s : Parser.parseScript(sql)) last = run(s);
        return last;
    }

    private Result run(Stmt s) {
        return switch (s) {
            case Select q -> select(q);
            case CreateTable c -> create(c);
            case DropTable d -> {
                if (tables.remove(d.name()) == null) throw new SqlException("unknown table " + d.name());
                yield Result.count(0);
            }
            case Insert i -> insert(i);
            case Update u -> update(u);
            case Delete d -> delete(d);
            case Begin b -> {
                if (snapshot != null) throw new SqlException("transaction already in progress");
                snapshot = copyTables(tables);
                yield Result.count(0);
            }
            case Commit c -> {
                if (snapshot == null) throw new SqlException("no transaction in progress");
                snapshot = null;
                yield Result.count(0);
            }
            case Rollback r -> {
                if (snapshot == null) throw new SqlException("no transaction in progress");
                tables = snapshot;
                snapshot = null;
                yield Result.count(0);
            }
        };
    }

    private static Map<String, Table> copyTables(Map<String, Table> src) {
        Map<String, Table> out = new LinkedHashMap<>();
        for (Map.Entry<String, Table> e : src.entrySet()) out.put(e.getKey(), e.getValue().copy());
        return out;
    }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw new SqlException("unknown table " + name);
        return t;
    }

    // ---- DDL / DML -------------------------------------------------------------------------------------------------

    private Result create(CreateTable c) {
        if (tables.containsKey(c.name())) throw new SqlException("table " + c.name() + " already exists");
        Set<String> seen = new HashSet<>();
        for (ColDef col : c.cols()) if (!seen.add(col.name())) throw new SqlException("duplicate column " + col.name());
        tables.put(c.name(), new Table(c.name(), c.cols()));
        return Result.count(0);
    }

    private static void checkValue(Table t, int col, Object v) {
        ColDef d = t.cols.get(col);
        if (v == null) {
            if (d.notNull()) throw new SqlException("column " + d.name() + " is NOT NULL");
            return;
        }
        boolean ok = switch (d.type()) {
            case INT -> v instanceof Long;
            case TEXT -> v instanceof String;
            case BOOL -> v instanceof Boolean;
        };
        if (!ok) throw new SqlException("type mismatch: column " + d.name() + " expects " + d.type() + ", got " + Eval.typeName(v));
    }

    private static void checkUnique(Table t, List<Object[]> rows) {
        if (t.pk < 0) return;
        Set<Object> keys = new HashSet<>();
        for (Object[] r : rows) if (!keys.add(r[t.pk])) throw new SqlException("duplicate key " + r[t.pk] + " in " + t.name);
    }

    private Result insert(Insert in) {
        Table t = table(in.table());
        int[] target;
        if (in.cols() == null) {
            target = new int[t.cols.size()];
            for (int i = 0; i < target.length; i++) target[i] = i;
        } else {
            target = new int[in.cols().size()];
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < target.length; i++) {
                String cn = in.cols().get(i);
                if (!seen.add(cn)) throw new SqlException("duplicate column " + cn);
                target[i] = t.indexOf(cn);
                if (target[i] < 0) throw new SqlException("unknown column " + cn);
            }
        }
        Scope none = new Scope(List.of());
        List<Object[]> added = new ArrayList<>();
        for (List<Expr> exprs : in.rows()) {
            if (exprs.size() != target.length) throw new SqlException("insert expects " + target.length + " values, got " + exprs.size());
            Object[] row = new Object[t.cols.size()];
            for (int i = 0; i < target.length; i++) {
                Expr bound = Eval.bind(exprs.get(i), none, false, "VALUES");
                row[target[i]] = Eval.eval(bound, none, new Object[0], null);
            }
            for (int c = 0; c < row.length; c++) checkValue(t, c, row[c]);
            added.add(row);
        }
        List<Object[]> all = new ArrayList<>(t.rows);
        all.addAll(added);
        checkUnique(t, all);
        t.rows = all;
        return Result.count(added.size());
    }

    private Result update(Update u) {
        Table t = table(u.table());
        Scope sc = t.scope(t.name);
        Expr where = u.where() == null ? null : Eval.bind(u.where(), sc, false, "WHERE");
        int[] cols = new int[u.sets().size()];
        Expr[] vals = new Expr[cols.length];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < cols.length; i++) {
            Assign a = u.sets().get(i);
            if (!seen.add(a.column())) throw new SqlException("duplicate column " + a.column());
            cols[i] = t.indexOf(a.column());
            if (cols[i] < 0) throw new SqlException("unknown column " + a.column());
            vals[i] = Eval.bind(a.value(), sc, false, "SET");
        }
        List<Object[]> out = new ArrayList<>(t.rows.size());
        int n = 0;
        for (Object[] row : t.rows) {
            if (where != null && !Eval.truth(Eval.eval(where, sc, row, null))) {
                out.add(row);
                continue;
            }
            Object[] nr = row.clone();
            for (int i = 0; i < cols.length; i++) nr[cols[i]] = Eval.eval(vals[i], sc, row, null); // old row on the right
            for (int c : cols) checkValue(t, c, nr[c]);
            out.add(nr);
            n++;
        }
        checkUnique(t, out);
        t.rows = out;
        return Result.count(n);
    }

    private Result delete(Delete d) {
        Table t = table(d.table());
        Scope sc = t.scope(t.name);
        Expr where = d.where() == null ? null : Eval.bind(d.where(), sc, false, "WHERE");
        List<Object[]> keep = new ArrayList<>();
        for (Object[] row : t.rows) {
            if (where == null || Eval.truth(Eval.eval(where, sc, row, null))) continue;
            keep.add(row);
        }
        int n = t.rows.size() - keep.size();
        t.rows = keep;
        return Result.count(n);
    }

    // ---- SELECT ----------------------------------------------------------------------------------------------------

    private record Rel(Scope scope, List<Object[]> rows) {}

    /** One output row: projected values plus its ORDER BY keys. */
    private record Out(Object[] vals, Object[] keys) {}

    private static List<Expr> conjuncts(Expr e) {
        List<Expr> out = new ArrayList<>();
        collect(e, out);
        return out;
    }

    private static void collect(Expr e, List<Expr> out) {
        if (e instanceof Bin b && b.op().equals("AND")) {
            collect(b.l(), out);
            collect(b.r(), out);
        } else {
            out.add(e);
        }
    }

    private Result select(Select q) {
        boolean optimized = planner == Planner.OPTIMIZED;
        List<String> plan = new ArrayList<>();

        // ---- static analysis (same for both planners, runs before any row is read)
        List<From> froms = new ArrayList<>();
        froms.add(q.from());
        for (Join j : q.joins()) froms.add(j.from());
        Set<String> aliasSeen = new HashSet<>();
        List<Table> ts = new ArrayList<>();
        List<Scope> scopes = new ArrayList<>();
        for (From f : froms) {
            Table t = table(f.table());
            if (!aliasSeen.add(f.alias())) throw new SqlException("duplicate alias " + f.alias());
            ts.add(t);
            scopes.add(t.scope(f.alias()));
        }
        Scope[] prefix = new Scope[froms.size()]; // prefix[i] = scope of tables 0..i
        prefix[0] = scopes.get(0);
        for (int i = 1; i < prefix.length; i++) prefix[i] = Scope.concat(prefix[i - 1], scopes.get(i));
        Scope full = prefix[prefix.length - 1];

        Expr[] on = new Expr[q.joins().size()];
        for (int i = 0; i < on.length; i++) on[i] = Eval.bind(q.joins().get(i).on(), prefix[i + 1], false, "ON");
        Expr where = q.where() == null ? null : Eval.bind(q.where(), full, false, "WHERE");
        List<Expr> groupBy = new ArrayList<>();
        for (Expr g : q.groupBy()) groupBy.add(Eval.bind(g, full, false, "GROUP BY"));

        // expand select items
        List<Expr> itemExprs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Map<String, Integer> aliasIndex = new HashMap<>(); // select alias -> index in the expanded item list
        for (Item it : q.items()) {
            if (it.star()) {
                boolean any = false;
                for (Scope.Entry e : full.entries()) {
                    if (it.starTable() != null && !e.alias().equals(it.starTable())) continue;
                    any = true;
                    itemExprs.add(new Col(e.alias(), e.name()));
                    names.add(e.name());
                }
                if (!any) throw new SqlException("unknown table " + it.starTable());
            } else {
                Expr b = Eval.bind(it.expr(), full, true, "SELECT");
                itemExprs.add(b);
                if (it.alias() != null) aliasIndex.putIfAbsent(it.alias(), itemExprs.size() - 1);
                names.add(it.alias() != null ? it.alias()
                        : it.expr() instanceof Col c ? c.name()
                        : it.expr() instanceof Agg a ? a.fn() : "col" + (itemExprs.size()));
            }
        }
        Expr having = q.having() == null ? null : Eval.bind(q.having(), full, true, "HAVING");
        // ORDER BY: ordinal, select alias, or expression
        int[] orderOrdinal = new int[q.order().size()]; // -1 = expression
        Expr[] orderExpr = new Expr[orderOrdinal.length];
        for (int i = 0; i < orderOrdinal.length; i++) {
            Expr k = q.order().get(i).expr();
            orderOrdinal[i] = -1;
            if (k instanceof Lit l && l.value() instanceof Long n) {
                if (n < 1 || n > itemExprs.size()) throw new SqlException("ORDER BY position " + n + " is out of range");
                orderOrdinal[i] = (int) (n - 1);
            } else if (k instanceof Col c && c.table() == null && aliasIndex.containsKey(c.name())) {
                orderOrdinal[i] = aliasIndex.get(c.name());
            }
            if (orderOrdinal[i] < 0) orderExpr[i] = Eval.bind(k, full, true, "ORDER BY");
        }
        boolean grouped = !groupBy.isEmpty() || having != null || itemExprs.stream().anyMatch(Eval::containsAgg)
                || Arrays.stream(orderExpr).anyMatch(e -> e != null && Eval.containsAgg(e));
        if (grouped) {
            for (Expr e : itemExprs) Eval.checkGrouped(e, groupBy);
            if (having != null) Eval.checkGrouped(having, groupBy);
            for (Expr e : orderExpr) if (e != null) Eval.checkGrouped(e, groupBy);
        }

        // ---- FROM / JOIN / WHERE
        List<Expr> wherePieces = where == null ? List.of() : conjuncts(where);
        List<Expr> finalFilters = new ArrayList<>(wherePieces);
        List<List<Expr>> scanFilters = new ArrayList<>();
        for (int i = 0; i < froms.size(); i++) scanFilters.add(new ArrayList<>());
        if (optimized) {
            // a conjunct over one relation may be filtered at its scan unless that relation is the right side of a LEFT JOIN
            for (Expr c : wherePieces) {
                Set<String> al = new HashSet<>();
                Eval.aliases(c, al);
                if (al.size() != 1) continue;
                String only = al.iterator().next();
                for (int i = 0; i < froms.size(); i++) {
                    boolean leftRight = i > 0 && q.joins().get(i - 1).left();
                    if (froms.get(i).alias().equals(only) && !leftRight) {
                        scanFilters.get(i).add(c);
                        finalFilters.remove(c);
                    }
                }
            }
        }
        Rel rel = scan(ts.get(0), froms.get(0), scopes.get(0), scanFilters.get(0), plan);
        for (int i = 1; i < froms.size(); i++) {
            Rel right = scan(ts.get(i), froms.get(i), scopes.get(i), scanFilters.get(i), plan);
            Join j = q.joins().get(i - 1);
            rel = join(rel, right, j.left(), on[i - 1], froms.get(i), optimized, plan);
        }
        plan.add("final-filter=" + finalFilters.size());
        List<Object[]> rows = new ArrayList<>();
        for (Object[] r : rel.rows()) {
            boolean keep = true;
            for (Expr f : finalFilters) {
                if (!Eval.truth(Eval.eval(f, rel.scope(), r, null))) { keep = false; break; }
            }
            if (keep) rows.add(r);
        }

        // ---- grouping / projection
        Scope sc = rel.scope();
        List<Out> outs = new ArrayList<>();
        if (!grouped) {
            for (Object[] r : rows) outs.add(project(itemExprs, orderOrdinal, orderExpr, sc, r, null));
        } else {
            Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
            if (groupBy.isEmpty()) {
                groups.put(List.of(), rows);
            } else {
                for (Object[] r : rows) {
                    List<Object> key = new ArrayList<>();
                    for (Expr g : groupBy) key.add(Eval.eval(g, sc, r, null));
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                }
            }
            for (List<Object[]> g : groups.values()) {
                Object[] rep = g.isEmpty() ? new Object[sc.size()] : g.get(0);
                Map<Agg, Object> memo = new HashMap<>();
                java.util.function.Function<Agg, Object> aggs = a -> memo.computeIfAbsent(a, x -> aggregate(x, sc, g));
                if (having != null && !Eval.truth(Eval.eval(having, sc, rep, aggs))) continue;
                outs.add(project(itemExprs, orderOrdinal, orderExpr, sc, rep, aggs));
            }
        }
        if (q.distinct()) {
            Map<List<Object>, Out> uniq = new LinkedHashMap<>();
            for (Out o : outs) uniq.putIfAbsent(Arrays.asList(o.vals()), o);
            outs = new ArrayList<>(uniq.values());
        }
        if (!q.order().isEmpty()) {
            List<OrderKey> ok = q.order();
            Comparator<Out> cmp = (a, b) -> {
                for (int i = 0; i < ok.size(); i++) {
                    Object x = a.keys()[i], y = b.keys()[i];
                    int c = x == null ? (y == null ? 0 : -1) : y == null ? 1 : Eval.compare(x, y);
                    if (c != 0) return ok.get(i).desc() ? -c : c;
                }
                return 0;
            };
            outs.sort(cmp); // List.sort is stable
        }
        int from = q.offset() == null ? 0 : (int) Math.min(q.offset(), outs.size());
        int to = q.limit() == null ? outs.size() : (int) Math.min((long) from + q.limit(), outs.size());
        List<List<Object>> result = new ArrayList<>();
        for (Out o : outs.subList(from, to)) result.add(Collections.unmodifiableList(new ArrayList<>(Arrays.asList(o.vals()))));
        lastPlan = List.copyOf(plan);
        return new Result(List.copyOf(names), List.copyOf(result), result.size());
    }

    private Out project(List<Expr> items, int[] ordOrdinal, Expr[] ordExpr, Scope sc, Object[] row,
            java.util.function.Function<Agg, Object> aggs) {
        Object[] vals = new Object[items.size()];
        for (int i = 0; i < vals.length; i++) vals[i] = Eval.eval(items.get(i), sc, row, aggs);
        Object[] keys = new Object[ordOrdinal.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = ordOrdinal[i] >= 0 ? vals[ordOrdinal[i]] : Eval.eval(ordExpr[i], sc, row, aggs);
        }
        return new Out(vals, keys);
    }

    private static Object aggregate(Agg a, Scope sc, List<Object[]> rows) {
        if (a.arg() == null) return (long) rows.size();
        ValueBag vals = new ValueBag();
        for (Object[] r : rows) {
            Object v = Eval.eval(a.arg(), sc, r, null);
            if (v != null) vals.add(v, a.distinct());
        }
        switch (a.fn()) {
            case "count":
                return (long) vals.list.size();
            case "sum": {
                if (vals.list.isEmpty()) return null;
                long s = 0;
                for (Object v : vals.list) {
                    if (!(v instanceof Long n)) throw new SqlException("SUM requires INT, got " + Eval.typeName(v));
                    try {
                        s = Math.addExact(s, n);
                    } catch (ArithmeticException e) {
                        throw new SqlException("integer overflow");
                    }
                }
                return s;
            }
            default: { // min / max
                Object best = null;
                for (Object v : vals.list) {
                    if (best == null) best = v;
                    else {
                        int c = Eval.compare(v, best);
                        if (a.fn().equals("min") ? c < 0 : c > 0) best = v;
                    }
                }
                return best;
            }
        }
    }

    /** Collects non-null values, optionally keeping only the first of each distinct value. */
    private static final class ValueBag {
        final List<Object> list = new ArrayList<>();
        private final Set<Object> seen = new LinkedHashSet<>();

        void add(Object v, boolean distinct) {
            if (distinct && !seen.add(v)) return;
            list.add(v);
        }
    }

    private Rel scan(Table t, From f, Scope sc, List<Expr> filters, List<String> plan) {
        plan.add("scan " + t.name + " as " + f.alias() + " filter=" + filters.size());
        List<Object[]> rows = new ArrayList<>();
        for (Object[] r : t.rows) {
            boolean keep = true;
            for (Expr e : filters) {
                if (!Eval.truth(Eval.eval(e, sc, r, null))) { keep = false; break; }
            }
            if (keep) rows.add(r);
        }
        return new Rel(sc, rows);
    }

    private Rel join(Rel left, Rel right, boolean isLeft, Expr on, From f, boolean optimized, List<String> plan) {
        Scope combined = Scope.concat(left.scope(), right.scope());
        List<Object[]> out = new ArrayList<>();
        List<Expr> keysL = new ArrayList<>(), keysR = new ArrayList<>(), residual = new ArrayList<>();
        if (optimized) {
            Set<String> leftAliases = new HashSet<>();
            for (Scope.Entry e : left.scope().entries()) leftAliases.add(e.alias());
            for (Expr c : conjuncts(on)) {
                if (c instanceof Bin b && b.op().equals("=")) {
                    Set<String> la = new HashSet<>(), ra = new HashSet<>();
                    Eval.aliases(b.l(), la);
                    Eval.aliases(b.r(), ra);
                    if (!la.isEmpty() && leftAliases.containsAll(la) && ra.equals(Set.of(f.alias()))) {
                        keysL.add(b.l());
                        keysR.add(b.r());
                        continue;
                    }
                    if (!ra.isEmpty() && leftAliases.containsAll(ra) && la.equals(Set.of(f.alias()))) {
                        keysL.add(b.r());
                        keysR.add(b.l());
                        continue;
                    }
                }
                residual.add(c);
            }
        }
        int width = right.scope().size();
        if (keysL.isEmpty()) {
            plan.add("nested-loop " + (isLeft ? "left " : "") + "join " + f.alias());
            for (Object[] l : left.rows()) {
                boolean matched = false;
                for (Object[] r : right.rows()) {
                    Object[] row = concat(l, r);
                    if (Eval.truth(Eval.eval(on, combined, row, null))) {
                        out.add(row);
                        matched = true;
                    }
                }
                if (!matched && isLeft) out.add(concat(l, new Object[width]));
            }
            return new Rel(combined, out);
        }
        plan.add("hash-join keys=" + keysL.size() + " residual=" + residual.size() + " " + (isLeft ? "left " : "") + f.alias());
        Class<?>[] kinds = new Class<?>[keysR.size()];
        Map<List<Object>, List<Object[]>> index = new HashMap<>();
        for (Object[] r : right.rows()) {
            List<Object> key = new ArrayList<>(keysR.size());
            boolean hasNull = false;
            for (int k = 0; k < keysR.size(); k++) {
                Object v = Eval.eval(keysR.get(k), right.scope(), r, null);
                if (v == null) hasNull = true;
                else kindCheck(kinds, k, v);
                key.add(v);
            }
            if (!hasNull) index.computeIfAbsent(key, x -> new ArrayList<>()).add(r);
        }
        for (Object[] l : left.rows()) {
            List<Object> key = new ArrayList<>(keysL.size());
            boolean hasNull = false;
            for (int k = 0; k < keysL.size(); k++) {
                Object v = Eval.eval(keysL.get(k), left.scope(), l, null);
                if (v == null) hasNull = true;
                else kindCheck(kinds, k, v);
                key.add(v);
            }
            boolean matched = false;
            if (!hasNull) {
                for (Object[] r : index.getOrDefault(key, List.of())) {
                    Object[] row = concat(l, r);
                    boolean ok = true;
                    for (Expr c : residual) {
                        if (!Eval.truth(Eval.eval(c, combined, row, null))) { ok = false; break; }
                    }
                    if (ok) {
                        out.add(row);
                        matched = true;
                    }
                }
            }
            if (!matched && isLeft) out.add(concat(l, new Object[width]));
        }
        return new Rel(combined, out);
    }

    /** Keeps hash-join key types honest: INT = TEXT is an error in the nested loop, so it must be one here too. */
    private static void kindCheck(Class<?>[] kinds, int k, Object v) {
        if (kinds[k] == null) kinds[k] = v.getClass();
        else if (kinds[k] != v.getClass()) {
            throw new SqlException("type mismatch: cannot compare join keys of different types");
        }
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
