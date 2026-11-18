package bench.minisql;

import bench.minisql.Expr.Agg;
import bench.minisql.Expr.Col;
import bench.minisql.Parser.Assign;
import bench.minisql.Parser.ColDef;
import bench.minisql.Parser.Create;
import bench.minisql.Parser.Delete;
import bench.minisql.Parser.Drop;
import bench.minisql.Parser.Insert;
import bench.minisql.Parser.Item;
import bench.minisql.Parser.Order;
import bench.minisql.Parser.Select;
import bench.minisql.Parser.Stmt;
import bench.minisql.Parser.Update;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A small in-memory SQL database. */
public final class Database {

    private static final class Table {
        final String name;
        final List<ColDef> cols;
        final Map<String, Integer> index = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();

        Table(String name, List<ColDef> cols) {
            this.name = name;
            this.cols = cols;
            for (int i = 0; i < cols.size(); i++) {
                index.put(cols.get(i).name(), i);
            }
        }

        int col(String c) {
            Integer i = index.get(c);
            if (i == null) {
                throw err("unknown column: " + c);
            }
            return i;
        }
    }

    private final Map<String, Table> tables = new HashMap<>();

    public Database() { }

    private static SqlException err(String m) {
        return new SqlException(m);
    }

    public synchronized Result execute(String sql) {
        if (sql == null) {
            throw err("statement is null");
        }
        Stmt st = new Parser(Lexer.lex(sql)).parseStatement();
        if (st instanceof Create c) {
            return doCreate(c);
        } else if (st instanceof Drop d) {
            if (tables.remove(d.table()) == null) {
                throw err("unknown table: " + d.table());
            }
            return empty(0);
        } else if (st instanceof Insert i) {
            return doInsert(i);
        } else if (st instanceof Update u) {
            return doUpdate(u);
        } else if (st instanceof Delete d) {
            return doDelete(d);
        }
        return doSelect((Select) st);
    }

    private static Result empty(int affected) {
        return new Result(List.of(), List.of(), affected);
    }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) {
            throw err("unknown table: " + name);
        }
        return t;
    }

    // ------------------------------------------------------------------ DDL / DML

    private Result doCreate(Create c) {
        if (tables.containsKey(c.table())) {
            throw err("table already exists: " + c.table());
        }
        Set<String> seen = new HashSet<>();
        for (ColDef d : c.cols()) {
            if (!seen.add(d.name())) {
                throw err("duplicate column: " + d.name());
            }
        }
        tables.put(c.table(), new Table(c.table(), List.copyOf(c.cols())));
        return empty(0);
    }

    private static void bind(Expr e, Table t) {
        if (e instanceof Col c) {
            if (t == null) {
                throw err("column reference not allowed here: " + c.name);
            }
            c.idx = t.col(c.name);
            return;
        }
        for (Expr k : e.kids()) {
            bind(k, t);
        }
    }

    private static Object checkStore(ColDef d, Object v) {
        if (v == null) {
            return null;
        }
        if (d.isInt() ? !(v instanceof Long) : !(v instanceof String)) {
            throw err("wrong type for column " + d.name());
        }
        return v;
    }

    private static boolean truth(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw err("condition must be boolean");
    }

    private Result doInsert(Insert ins) {
        Table t = table(ins.table());
        int n = t.cols.size();
        int[] targets;
        if (ins.cols() == null) {
            targets = new int[n];
            for (int i = 0; i < n; i++) {
                targets[i] = i;
            }
        } else {
            targets = new int[ins.cols().size()];
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < targets.length; i++) {
                targets[i] = t.col(ins.cols().get(i));
                if (!seen.add(targets[i])) {
                    throw err("duplicate column in insert list");
                }
            }
        }
        for (List<Expr> tuple : ins.tuples()) {
            if (tuple.size() != targets.length) {
                throw err("value count does not match column count");
            }
            for (Expr e : tuple) {
                if (Expr.containsAgg(e)) {
                    throw err("aggregate not allowed in INSERT");
                }
                bind(e, null);
            }
        }
        List<Object[]> add = new ArrayList<>(ins.tuples().size());
        for (List<Expr> tuple : ins.tuples()) {
            Object[] row = new Object[n];
            for (int i = 0; i < targets.length; i++) {
                row[targets[i]] = checkStore(t.cols.get(targets[i]), tuple.get(i).eval(null, null));
            }
            add.add(row);
        }
        t.rows.addAll(add);
        return empty(add.size());
    }

    private Result doUpdate(Update u) {
        Table t = table(u.table());
        int[] idx = new int[u.sets().size()];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < idx.length; i++) {
            Assign a = u.sets().get(i);
            idx[i] = t.col(a.col());
            if (!seen.add(idx[i])) {
                throw err("column set twice: " + a.col());
            }
            if (Expr.containsAgg(a.value())) {
                throw err("aggregate not allowed in UPDATE");
            }
            bind(a.value(), t);
        }
        if (u.where() != null) {
            if (Expr.containsAgg(u.where())) {
                throw err("aggregate not allowed in WHERE");
            }
            bind(u.where(), t);
        }
        Map<Integer, Object[]> changes = new LinkedHashMap<>();
        for (int r = 0; r < t.rows.size(); r++) {
            Object[] row = t.rows.get(r);
            if (u.where() != null && !truth(u.where().eval(row, null))) {
                continue;
            }
            Object[] nr = row.clone();
            for (int i = 0; i < idx.length; i++) {
                nr[idx[i]] = checkStore(t.cols.get(idx[i]), u.sets().get(i).value().eval(row, null));
            }
            changes.put(r, nr);
        }
        for (Map.Entry<Integer, Object[]> e : changes.entrySet()) {
            t.rows.set(e.getKey(), e.getValue());
        }
        return empty(changes.size());
    }

    private Result doDelete(Delete d) {
        Table t = table(d.table());
        if (d.where() == null) {
            int n = t.rows.size();
            t.rows = new ArrayList<>();
            return empty(n);
        }
        if (Expr.containsAgg(d.where())) {
            throw err("aggregate not allowed in WHERE");
        }
        bind(d.where(), t);
        List<Object[]> keep = new ArrayList<>();
        for (Object[] row : t.rows) {
            if (!truth(d.where().eval(row, null))) {
                keep.add(row);
            }
        }
        int removed = t.rows.size() - keep.size();
        t.rows = keep;
        return empty(removed);
    }

    // ------------------------------------------------------------------ SELECT

    private static final class Entry {
        final Object[] out;
        final Object[] keys;

        Entry(Object[] out, Object[] keys) {
            this.out = out;
            this.keys = keys;
        }
    }

    private static void checkGrouped(Expr e, Set<Integer> grouped) {
        if (e instanceof Agg a) {
            if (a.arg != null && Expr.containsAgg(a.arg)) {
                throw err("aggregates cannot be nested");
            }
            return;
        }
        if (e instanceof Col c) {
            if (!grouped.contains(c.idx)) {
                throw err("column " + c.name + " must be grouped or aggregated");
            }
            return;
        }
        for (Expr k : e.kids()) {
            checkGrouped(k, grouped);
        }
    }

    private Result doSelect(Select s) {
        Table t = table(s.table());
        // output names
        List<String> names = new ArrayList<>();
        if (s.star()) {
            for (ColDef c : t.cols) {
                names.add(c.name());
            }
        } else {
            for (int i = 0; i < s.items().size(); i++) {
                Item it = s.items().get(i);
                if (it.alias() != null) {
                    names.add(it.alias());
                } else if (it.expr() instanceof Col c) {
                    names.add(c.name);
                } else {
                    names.add("col" + (i + 1));
                }
            }
        }
        int ncols = names.size();

        // classify order items
        int m = s.order().size();
        int[] outRef = new int[m];
        boolean[] desc = new boolean[m];
        for (int i = 0; i < m; i++) {
            Order o = s.order().get(i);
            desc[i] = o.desc();
            outRef[i] = -1;
            if (o.bareInt() >= 0) {
                if (o.bareInt() < 1 || o.bareInt() > ncols) {
                    throw err("ORDER BY position out of range: " + o.bareInt());
                }
                outRef[i] = o.bareInt() - 1;
            } else if (o.bareName() != null && names.contains(o.bareName())) {
                outRef[i] = names.indexOf(o.bareName());
            }
        }

        // validation / binding
        if (s.where() != null) {
            if (Expr.containsAgg(s.where())) {
                throw err("aggregate not allowed in WHERE");
            }
            bind(s.where(), t);
        }
        boolean aggregate = s.groupBy() != null || s.having() != null;
        for (Item it : s.items()) {
            bind(it.expr(), t);
            aggregate |= Expr.containsAgg(it.expr());
        }
        if (s.having() != null) {
            bind(s.having(), t);
        }
        for (int i = 0; i < m; i++) {
            if (outRef[i] < 0) {
                Expr e = s.order().get(i).expr();
                bind(e, t);
                aggregate |= Expr.containsAgg(e);
            }
        }
        Set<Integer> grouped = new HashSet<>();
        List<Integer> groupIdx = new ArrayList<>();
        if (s.groupBy() != null) {
            for (String g : s.groupBy()) {
                int gi = t.col(g);
                grouped.add(gi);
                groupIdx.add(gi);
            }
        }
        if (aggregate) {
            if (s.star()) {
                throw err("* not allowed in an aggregate query");
            }
            for (Item it : s.items()) {
                checkGrouped(it.expr(), grouped);
            }
            if (s.having() != null) {
                checkGrouped(s.having(), grouped);
            }
            for (int i = 0; i < m; i++) {
                if (outRef[i] < 0) {
                    checkGrouped(s.order().get(i).expr(), grouped);
                }
            }
        }

        // filter
        List<Object[]> src;
        if (s.where() == null) {
            src = t.rows;
        } else {
            src = new ArrayList<>();
            for (Object[] row : t.rows) {
                if (truth(s.where().eval(row, null))) {
                    src.add(row);
                }
            }
        }

        List<Entry> entries = new ArrayList<>();
        int nItems = s.items().size();
        if (!aggregate) {
            for (Object[] row : src) {
                Object[] out;
                if (s.star()) {
                    out = row.clone();
                } else {
                    out = new Object[nItems];
                    for (int i = 0; i < nItems; i++) {
                        out[i] = s.items().get(i).expr().eval(row, null);
                    }
                }
                entries.add(new Entry(out, orderKeys(s, outRef, out, row, null)));
            }
        } else {
            List<List<Object[]>> groups = new ArrayList<>();
            if (s.groupBy() != null) {
                Map<List<Object>, List<Object[]>> map = new LinkedHashMap<>();
                for (Object[] row : src) {
                    Object[] key = new Object[groupIdx.size()];
                    for (int i = 0; i < key.length; i++) {
                        key[i] = row[groupIdx.get(i)];
                    }
                    map.computeIfAbsent(Arrays.asList(key), k -> new ArrayList<>()).add(row);
                }
                groups.addAll(map.values());
            } else {
                groups.add(src);
            }
            for (List<Object[]> grp : groups) {
                Object[] rep = grp.isEmpty() ? null : grp.get(0);
                if (s.having() != null && !truth(s.having().eval(rep, grp))) {
                    continue;
                }
                Object[] out = new Object[nItems];
                for (int i = 0; i < nItems; i++) {
                    out[i] = s.items().get(i).expr().eval(rep, grp);
                }
                entries.add(new Entry(out, orderKeys(s, outRef, out, rep, grp)));
            }
        }

        if (m > 0) {
            entries.sort((a, b) -> {
                for (int i = 0; i < m; i++) {
                    int c = compareKeys(a.keys[i], b.keys[i]);
                    if (c != 0) {
                        return desc[i] ? -c : c;
                    }
                }
                return 0;
            });
        }

        long from = s.offset() == null ? 0 : s.offset();
        long total = entries.size();
        from = Math.min(from, total);
        long to = s.limit() == null ? total : Math.min(total, from + s.limit());
        // from + limit may overflow only for huge limits
        if (to < from) {
            to = total;
        }
        List<List<Object>> rows = new ArrayList<>((int) (to - from));
        for (long i = from; i < to; i++) {
            rows.add(Collections.unmodifiableList(Arrays.asList(entries.get((int) i).out)));
        }
        return new Result(Collections.unmodifiableList(new ArrayList<>(names)),
                Collections.unmodifiableList(rows), 0);
    }

    private static Object[] orderKeys(Select s, int[] outRef, Object[] out, Object[] row, List<Object[]> grp) {
        int m = outRef.length;
        Object[] keys = new Object[m];
        for (int i = 0; i < m; i++) {
            keys[i] = outRef[i] >= 0 ? out[outRef[i]] : s.order().get(i).expr().eval(row, grp);
        }
        return keys;
    }

    private static int compareKeys(Object a, Object b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        throw err("cannot compare values of different types in ORDER BY");
    }

    public record Result(List<String> columns, List<List<Object>> rows, int affected) { }
}
