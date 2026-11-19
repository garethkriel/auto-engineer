package bench.minisql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** A small in-memory SQL database. Not thread-safe. */
public final class Database {

    /** Result of a statement. */
    public record Result(List<String> columns, List<List<Object>> rows, int affected) { }

    private static final class Table {
        final List<String> names = new ArrayList<>();
        final List<Boolean> isInt = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();

        int col(String name) {
            Integer i = index.get(name);
            if (i == null) {
                throw new SqlException("unknown column '" + name + "'");
            }
            return i;
        }

        /** Checks that v can be stored in column i. */
        void check(int i, Object v) {
            if (v == null) {
                return;
            }
            if (isInt.get(i) ? !(v instanceof Long) : !(v instanceof String)) {
                throw new SqlException("type mismatch for column '" + names.get(i) + "'");
            }
        }
    }

    private final Map<String, Table> tables = new HashMap<>();

    public Database() { }

    /** Executes exactly one statement; a failing statement changes nothing. */
    public Result execute(String sql) {
        if (sql == null) {
            throw new SqlException("null statement");
        }
        Ast.Stmt st = Parser.parse(sql);
        return switch (st) {
            case Ast.Create c -> create(c);
            case Ast.Drop d -> drop(d);
            case Ast.Insert i -> insert(i);
            case Ast.Update u -> update(u);
            case Ast.Delete d -> delete(d);
            case Ast.Select s -> select(s);
        };
    }

    private static Result empty(int affected) {
        return new Result(List.of(), List.of(), affected);
    }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) {
            throw new SqlException("unknown table '" + name + "'");
        }
        return t;
    }

    // ---------------------------------------------------------------- expression analysis

    private static void walk(Expr e, Consumer<Expr> f) {
        if (e == null) {
            return;
        }
        f.accept(e);
        switch (e) {
            case Expr.Neg n -> walk(n.e(), f);
            case Expr.Not n -> walk(n.e(), f);
            case Expr.IsNull n -> walk(n.e(), f);
            case Expr.Bin b -> {
                walk(b.l(), f);
                walk(b.r(), f);
            }
            case Expr.Agg a -> walk(a.arg(), f);
            default -> { }
        }
    }

    private static boolean hasAgg(Expr e) {
        boolean[] found = {false};
        walk(e, x -> {
            if (x instanceof Expr.Agg) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private static void noAgg(Expr e, String where) {
        if (hasAgg(e)) {
            throw new SqlException("aggregates are not allowed in " + where);
        }
    }

    private static void checkNoNesting(Expr e) {
        walk(e, x -> {
            if (x instanceof Expr.Agg a && hasAgg(a.arg())) {
                throw new SqlException("aggregates cannot be nested");
            }
        });
    }

    private static void resolve(Expr e, Table t) {
        walk(e, x -> {
            if (x instanceof Expr.Col c) {
                c.idx = t.col(c.name);
            }
        });
    }

    /** In an aggregate query, columns outside aggregate arguments must be grouped. */
    private static void checkGrouped(Expr e, Set<Integer> grouped) {
        if (e == null) {
            return;
        }
        switch (e) {
            case Expr.Col c -> {
                if (!grouped.contains(c.idx)) {
                    throw new SqlException("column '" + c.name + "' must be grouped or aggregated");
                }
            }
            case Expr.Neg n -> checkGrouped(n.e(), grouped);
            case Expr.Not n -> checkGrouped(n.e(), grouped);
            case Expr.IsNull n -> checkGrouped(n.e(), grouped);
            case Expr.Bin b -> {
                checkGrouped(b.l(), grouped);
                checkGrouped(b.r(), grouped);
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- DDL

    private Result create(Ast.Create c) {
        if (tables.containsKey(c.table())) {
            throw new SqlException("table '" + c.table() + "' already exists");
        }
        Table t = new Table();
        for (Ast.ColDef d : c.cols()) {
            if (t.index.containsKey(d.name())) {
                throw new SqlException("duplicate column '" + d.name() + "'");
            }
            t.index.put(d.name(), t.names.size());
            t.names.add(d.name());
            t.isInt.add(d.isInt());
        }
        tables.put(c.table(), t);
        return empty(0);
    }

    private Result drop(Ast.Drop d) {
        table(d.table());
        tables.remove(d.table());
        return empty(0);
    }

    // ---------------------------------------------------------------- DML

    private Result insert(Ast.Insert in) {
        Table t = table(in.table());
        int n = t.names.size();
        int[] targets;
        if (in.cols() == null) {
            targets = new int[n];
            for (int i = 0; i < n; i++) {
                targets[i] = i;
            }
        } else {
            targets = new int[in.cols().size()];
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < targets.length; i++) {
                targets[i] = t.col(in.cols().get(i));
                if (!seen.add(targets[i])) {
                    throw new SqlException("duplicate column in insert list");
                }
            }
        }
        for (List<Expr> row : in.rows()) {
            for (Expr e : row) {
                noAgg(e, "INSERT");
                walk(e, x -> {
                    if (x instanceof Expr.Col) {
                        throw new SqlException("INSERT values cannot refer to columns");
                    }
                });
            }
        }
        List<Object[]> fresh = new ArrayList<>(in.rows().size());
        for (List<Expr> row : in.rows()) {
            if (row.size() != targets.length) {
                throw new SqlException("wrong number of values");
            }
            Object[] r = new Object[n];
            for (int i = 0; i < targets.length; i++) {
                Object v = Eval.eval(row.get(i), null, null);
                t.check(targets[i], v);
                r[targets[i]] = v;
            }
            fresh.add(r);
        }
        t.rows.addAll(fresh);
        return empty(fresh.size());
    }

    private Result update(Ast.Update u) {
        Table t = table(u.table());
        int k = u.cols().size();
        int[] targets = new int[k];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < k; i++) {
            targets[i] = t.col(u.cols().get(i));
            if (!seen.add(targets[i])) {
                throw new SqlException("column assigned twice");
            }
        }
        for (Expr e : u.values()) {
            noAgg(e, "UPDATE");
            resolve(e, t);
        }
        noAgg(u.where(), "WHERE");
        resolve(u.where(), t);
        List<Integer> hit = new ArrayList<>();
        List<Object[]> newVals = new ArrayList<>();
        for (int ri = 0; ri < t.rows.size(); ri++) {
            Object[] row = t.rows.get(ri);
            if (!matches(u.where(), row)) {
                continue;
            }
            Object[] nv = new Object[k];
            for (int i = 0; i < k; i++) {
                nv[i] = Eval.eval(u.values().get(i), row, null);
                t.check(targets[i], nv[i]);
            }
            hit.add(ri);
            newVals.add(nv);
        }
        for (int h = 0; h < hit.size(); h++) {
            Object[] row = t.rows.get(hit.get(h)).clone();
            for (int i = 0; i < k; i++) {
                row[targets[i]] = newVals.get(h)[i];
            }
            t.rows.set(hit.get(h), row);
        }
        return empty(hit.size());
    }

    private Result delete(Ast.Delete d) {
        Table t = table(d.table());
        noAgg(d.where(), "WHERE");
        resolve(d.where(), t);
        List<Object[]> keep = new ArrayList<>();
        int removed = 0;
        for (Object[] row : t.rows) {
            if (matches(d.where(), row)) {
                removed++;
            } else {
                keep.add(row);
            }
        }
        t.rows = keep;
        return empty(removed);
    }

    /** True when cond is absent or evaluates to TRUE. */
    private static boolean matches(Expr cond, Object[] row) {
        if (cond == null) {
            return true;
        }
        return truth(Eval.eval(cond, row, null));
    }

    private static boolean truth(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new SqlException("condition must be boolean");
    }

    // ---------------------------------------------------------------- SELECT

    private Result select(Ast.Select s) {
        Table t = table(s.table());
        List<String> names = new ArrayList<>();
        List<Expr> outExprs = new ArrayList<>();
        boolean star = s.items() == null;
        if (star) {
            for (int i = 0; i < t.names.size(); i++) {
                Expr.Col c = new Expr.Col(t.names.get(i));
                c.idx = i;
                names.add(t.names.get(i));
                outExprs.add(c);
            }
        } else {
            int pos = 1;
            for (Ast.Item it : s.items()) {
                resolve(it.expr(), t);
                outExprs.add(it.expr());
                if (it.alias() != null) {
                    names.add(it.alias());
                } else if (it.expr() instanceof Expr.Col c) {
                    names.add(c.name);
                } else {
                    names.add("col" + pos);
                }
                pos++;
            }
        }
        // ORDER BY items: output column index, or an expression.
        int nOrder = s.order().size();
        int[] orderOut = new int[nOrder];
        Expr[] orderExpr = new Expr[nOrder];
        for (int i = 0; i < nOrder; i++) {
            Ast.OrderItem oi = s.order().get(i);
            orderOut[i] = -1;
            if (oi.pos() != null) {
                if (oi.pos() < 1 || oi.pos() > names.size()) {
                    throw new SqlException("ORDER BY position out of range");
                }
                orderOut[i] = (int) (oi.pos() - 1);
            } else if (oi.name() != null && names.contains(oi.name())) {
                orderOut[i] = names.indexOf(oi.name());
            } else {
                orderExpr[i] = oi.expr();
                resolve(oi.expr(), t);
            }
        }
        resolve(s.where(), t);
        resolve(s.having(), t);
        int[] groupIdx = new int[s.groupBy().size()];
        Set<Integer> grouped = new HashSet<>();
        for (int i = 0; i < groupIdx.length; i++) {
            groupIdx[i] = t.col(s.groupBy().get(i));
            grouped.add(groupIdx[i]);
        }
        noAgg(s.where(), "WHERE");
        boolean isAgg = groupIdx.length > 0 || s.having() != null;
        if (!star) {
            for (Expr e : outExprs) {
                checkNoNesting(e);
                isAgg |= hasAgg(e);
            }
        }
        for (Expr e : orderExpr) {
            if (e != null) {
                checkNoNesting(e);
                isAgg |= hasAgg(e);
            }
        }
        checkNoNesting(s.having());
        if (isAgg) {
            if (star) {
                throw new SqlException("* is not allowed in an aggregate query");
            }
            for (Expr e : outExprs) {
                checkGrouped(e, grouped);
            }
            checkGrouped(s.having(), grouped);
            for (Expr e : orderExpr) {
                checkGrouped(e, grouped);
            }
        }

        List<Object[]> src = new ArrayList<>();
        for (Object[] row : t.rows) {
            if (matches(s.where(), row)) {
                src.add(row);
            }
        }

        // Each unit is one output row's source: a table row, or a group of rows.
        List<Object[]> single = null;
        List<List<Object[]>> groups = null;
        if (isAgg) {
            groups = new ArrayList<>();
            if (groupIdx.length == 0) {
                groups.add(src);
            } else {
                Map<List<Object>, List<Object[]>> map = new LinkedHashMap<>();
                for (Object[] row : src) {
                    Object[] key = new Object[groupIdx.length];
                    for (int i = 0; i < key.length; i++) {
                        key[i] = row[groupIdx[i]];
                    }
                    map.computeIfAbsent(Arrays.asList(key), k -> new ArrayList<>()).add(row);
                }
                groups.addAll(map.values());
            }
            List<List<Object[]>> kept = new ArrayList<>();
            for (List<Object[]> g : groups) {
                if (s.having() == null || truth(Eval.eval(s.having(), null, g))) {
                    kept.add(g);
                }
            }
            groups = kept;
        } else {
            single = src;
        }
        int units = isAgg ? groups.size() : single.size();

        int width = outExprs.size();
        Object[][] out = new Object[units][width];
        for (int u = 0; u < units; u++) {
            for (int c = 0; c < width; c++) {
                out[u][c] = isAgg ? Eval.eval(outExprs.get(c), null, groups.get(u))
                                  : Eval.eval(outExprs.get(c), single.get(u), null);
            }
        }

        Integer[] order = new Integer[units];
        for (int i = 0; i < units; i++) {
            order[i] = i;
        }
        if (nOrder > 0) {
            Object[][] keys = new Object[units][nOrder];
            for (int u = 0; u < units; u++) {
                for (int j = 0; j < nOrder; j++) {
                    if (orderOut[j] >= 0) {
                        keys[u][j] = out[u][orderOut[j]];
                    } else {
                        keys[u][j] = isAgg ? Eval.eval(orderExpr[j], null, groups.get(u))
                                           : Eval.eval(orderExpr[j], single.get(u), null);
                    }
                }
            }
            Arrays.sort(order, (a, b) -> {
                for (int j = 0; j < nOrder; j++) {
                    Object x = keys[a][j];
                    Object y = keys[b][j];
                    int c;
                    if (x == null || y == null) {
                        c = x == null ? (y == null ? 0 : -1) : 1;
                    } else {
                        c = Eval.compare(x, y);
                    }
                    if (c != 0) {
                        return s.order().get(j).desc() ? -c : c;
                    }
                }
                return 0;
            });
        }

        long skip = s.offset() < 0 ? 0 : Math.min(s.offset(), units);
        long take = s.limit() < 0 ? units - skip : Math.min(s.limit(), units - skip);
        List<List<Object>> rows = new ArrayList<>((int) take);
        for (long i = skip; i < skip + take; i++) {
            rows.add(Collections.unmodifiableList(Arrays.asList(out[order[(int) i]])));
        }
        return new Result(Collections.unmodifiableList(new ArrayList<>(names)), Collections.unmodifiableList(rows), 0);
    }
}
