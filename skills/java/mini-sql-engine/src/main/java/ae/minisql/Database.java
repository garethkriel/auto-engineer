package ae.minisql;

import ae.minisql.Ast.*;
import ae.minisql.Eval.Ctx;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** In-memory SQL database. Not thread-safe: callers serialise access. */
public final class Database {
    private Map<String, Table> catalog = new LinkedHashMap<>();
    private Map<String, Table> saved; // non-null inside BEGIN ... COMMIT/ROLLBACK

    /** Runs exactly one statement (a trailing ';' is allowed). */
    public Result execute(String sql) {
        List<Stmt> stmts = Parser.parseScript(sql);
        if (stmts.size() != 1) {
            throw new SqlException("expected exactly one statement, found " + stmts.size());
        }
        return run(stmts.get(0));
    }

    /** Parses the whole script first (a syntax error anywhere runs nothing), then runs each statement in order. */
    public List<Result> executeScript(String sql) {
        List<Result> out = new ArrayList<>();
        for (Stmt s : Parser.parseScript(sql)) {
            out.add(run(s));
        }
        return out;
    }

    private Result run(Stmt stmt) {
        try {
            return switch (stmt) {
                case CreateTable s -> createTable(s);
                case DropTable s -> dropTable(s);
                case Insert s -> insert(s);
                case Update s -> update(s);
                case Delete s -> delete(s);
                case Select s -> select(s);
                case Begin s -> begin();
                case Commit s -> commit();
                case Rollback s -> rollback();
            };
        } catch (ArithmeticException e) {
            throw new SqlException("integer overflow");
        }
    }

    // ---- DDL and transactions ----

    private Table table(String name) {
        Table t = catalog.get(name);
        if (t == null) {
            throw new SqlException("unknown table " + name);
        }
        return t;
    }

    private Result createTable(CreateTable s) {
        if (catalog.containsKey(s.name())) {
            throw new SqlException("table " + s.name() + " already exists");
        }
        catalog.put(s.name(), new Table(s.name(), List.copyOf(s.columns()), List.of()));
        return Result.ofCount(0);
    }

    private Result dropTable(DropTable s) {
        table(s.name());
        catalog.remove(s.name());
        return Result.ofCount(0);
    }

    private Result begin() {
        if (saved != null) {
            throw new SqlException("transaction already active");
        }
        saved = snapshot();
        return Result.ofCount(0);
    }

    private Result commit() {
        if (saved == null) {
            throw new SqlException("no active transaction");
        }
        saved = null;
        return Result.ofCount(0);
    }

    private Result rollback() {
        if (saved == null) {
            throw new SqlException("no active transaction");
        }
        catalog = saved;
        saved = null;
        return Result.ofCount(0);
    }

    private Map<String, Table> snapshot() {
        Map<String, Table> copy = new LinkedHashMap<>();
        catalog.forEach((k, t) -> copy.put(k, t.copy()));
        return copy;
    }

    // ---- DML ----

    private static void requireNoAggregate(Expr e, String where) {
        if (e != null && Ast.containsAggregate(e)) {
            throw new SqlException("aggregate functions are not allowed in " + where);
        }
    }

    private static void resolveColumns(Expr e, Schema schema) {
        Ast.walk(e, x -> {
            if (x instanceof Col c) {
                schema.find(c.qualifier(), c.name(), c.pos());
            }
        });
    }

    private Result insert(Insert s) {
        Table t = table(s.table());
        int width = t.columns.size();
        int[] target;
        if (s.columns() == null) {
            target = new int[width];
            for (int k = 0; k < width; k++) {
                target[k] = k;
            }
        } else {
            target = new int[s.columns().size()];
            Set<Integer> used = new HashSet<>();
            for (int k = 0; k < target.length; k++) {
                target[k] = t.indexOf(s.columns().get(k), -1);
                if (!used.add(target[k])) {
                    throw new SqlException("column " + s.columns().get(k) + " listed twice");
                }
            }
        }
        List<Object[]> next = new ArrayList<>(t.rows);
        Ctx constant = new Ctx(Schema.EMPTY, java.util.Collections.singletonList(new Object[0]), false);
        for (List<Expr> exprs : s.rows()) {
            if (exprs.size() != target.length) {
                throw new SqlException("expected " + target.length + " values but got " + exprs.size());
            }
            Object[] row = new Object[width];
            for (int k = 0; k < target.length; k++) {
                requireNoAggregate(exprs.get(k), "VALUES");
                row[target[k]] = Eval.eval(exprs.get(k), constant);
            }
            next.add(row);
        }
        t.validate(next);
        t.rows = List.copyOf(next);
        return Result.ofCount(s.rows().size());
    }

    private Result update(Update s) {
        Table t = table(s.table());
        Schema schema = t.schema(t.name);
        requireNoAggregate(s.where(), "WHERE");
        if (s.where() != null) {
            resolveColumns(s.where(), schema);
        }
        int[] cols = new int[s.assignments().size()];
        for (int k = 0; k < cols.length; k++) {
            Assign a = s.assignments().get(k);
            requireNoAggregate(a.value(), "SET");
            resolveColumns(a.value(), schema);
            cols[k] = t.indexOf(a.column(), a.pos());
        }
        List<Object[]> next = new ArrayList<>(t.rows.size());
        int changed = 0;
        for (Object[] row : t.rows) {
            Ctx ctx = new Ctx(schema, java.util.Collections.singletonList(row), false);
            if (s.where() != null && !Eval.isTrue(Eval.eval(s.where(), ctx), "WHERE", -1)) {
                next.add(row);
                continue;
            }
            Object[] copy = row.clone();
            for (int k = 0; k < cols.length; k++) {
                copy[cols[k]] = Eval.eval(s.assignments().get(k).value(), ctx); // right sides see the OLD row
            }
            next.add(copy);
            changed++;
        }
        t.validate(next);
        t.rows = List.copyOf(next);
        return Result.ofCount(changed);
    }

    private Result delete(Delete s) {
        Table t = table(s.table());
        Schema schema = t.schema(t.name);
        requireNoAggregate(s.where(), "WHERE");
        if (s.where() != null) {
            resolveColumns(s.where(), schema);
        }
        List<Object[]> keep = new ArrayList<>();
        for (Object[] row : t.rows) {
            boolean hit = s.where() == null
                    || Eval.isTrue(Eval.eval(s.where(), new Ctx(schema, java.util.Collections.singletonList(row), false)), "WHERE", -1);
            if (!hit) {
                keep.add(row);
            }
        }
        int removed = t.rows.size() - keep.size();
        t.rows = List.copyOf(keep);
        return Result.ofCount(removed);
    }

    // ---- SELECT ----

    private record Out(String label, Expr expr, String alias) {}

    private record Produced(List<Object> values, List<Object> keys) {}

    private Result select(Select s) {
        Schema schema = Schema.EMPTY;
        List<Object[]> rows = java.util.Collections.singletonList(new Object[0]);
        if (s.from() != null) {
            Table base = table(s.from().name());
            schema = base.schema(s.from().alias());
            rows = base.rows;
            Set<String> aliases = new HashSet<>(List.of(s.from().alias()));
            for (Join j : s.joins()) {
                if (!aliases.add(j.table().alias())) {
                    throw new SqlException("duplicate table alias " + j.table().alias(), j.table().pos());
                }
                Table right = table(j.table().name());
                Schema rs = right.schema(j.table().alias());
                Schema combined = schema.concat(rs);
                requireNoAggregate(j.on(), "ON");
                resolveColumns(j.on(), combined);
                rows = join(rows, right.rows, combined, j, rs.size());
                schema = combined;
            }
        }
        requireNoAggregate(s.where(), "WHERE");
        if (s.where() != null) {
            resolveColumns(s.where(), schema);
            List<Object[]> kept = new ArrayList<>();
            for (Object[] row : rows) {
                if (Eval.isTrue(Eval.eval(s.where(), new Ctx(schema, java.util.Collections.singletonList(row), false)), "WHERE", -1)) {
                    kept.add(row);
                }
            }
            rows = kept;
        }
        List<Out> outs = expandItems(s.items(), schema, s.from() != null);
        boolean aggregate = !s.groupBy().isEmpty() || outs.stream().anyMatch(o -> Ast.containsAggregate(o.expr()))
                || (s.having() != null && Ast.containsAggregate(s.having()))
                || s.orderBy().stream().anyMatch(k -> Ast.containsAggregate(k.expr()));
        if (!aggregate && s.having() != null) {
            throw new SqlException("HAVING requires GROUP BY or aggregates");
        }
        List<Out> sortKeys = new ArrayList<>(); // one expression per ORDER BY key (output refs resolved below)
        int[] outputRef = new int[s.orderBy().size()];
        for (int k = 0; k < outputRef.length; k++) {
            outputRef[k] = resolveOrderKey(s.orderBy().get(k).expr(), outs);
            sortKeys.add(new Out(null, s.orderBy().get(k).expr(), null));
        }
        List<List<Object[]>> groups = new ArrayList<>();
        if (aggregate) {
            groups = group(s, rows, schema, outs, outputRef);
        } else {
            for (Out o : outs) {
                resolveColumns(o.expr(), schema);
            }
            for (int k = 0; k < outputRef.length; k++) {
                if (outputRef[k] < 0) {
                    resolveColumns(sortKeys.get(k).expr(), schema);
                }
            }
            for (Object[] row : rows) {
                groups.add(java.util.Collections.singletonList(row));
            }
        }
        List<Produced> produced = new ArrayList<>();
        for (List<Object[]> g : groups) {
            Ctx ctx = new Ctx(schema, g, aggregate);
            if (s.having() != null && !Eval.isTrue(Eval.eval(s.having(), ctx), "HAVING", -1)) {
                continue;
            }
            List<Object> values = new ArrayList<>();
            for (Out o : outs) {
                values.add(Eval.eval(o.expr(), ctx));
            }
            List<Object> keys = new ArrayList<>();
            for (int k = 0; k < outputRef.length; k++) {
                keys.add(outputRef[k] >= 0 ? values.get(outputRef[k]) : Eval.eval(sortKeys.get(k).expr(), ctx));
            }
            produced.add(new Produced(values, keys));
        }
        if (s.distinct()) {
            Set<List<Object>> seen = new LinkedHashSet<>();
            produced.removeIf(p -> !seen.add(p.values()));
        }
        if (!s.orderBy().isEmpty()) {
            produced.sort(orderComparator(s.orderBy()));
        }
        int from = s.offset() == null ? 0 : Math.min(s.offset(), produced.size());
        int to = produced.size();
        if (s.limit() != null) {
            to = (int) Math.min((long) to, (long) from + s.limit());
        }
        List<List<Object>> result = new ArrayList<>();
        for (Produced p : produced.subList(from, to)) {
            result.add(p.values());
        }
        return new Result(outs.stream().map(Out::label).toList(), result, 0);
    }

    private List<Object[]> join(List<Object[]> left, List<Object[]> right, Schema combined, Join j, int rightWidth) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] l : left) {
            boolean matched = false;
            for (Object[] r : right) {
                Object[] both = new Object[l.length + r.length];
                System.arraycopy(l, 0, both, 0, l.length);
                System.arraycopy(r, 0, both, l.length, r.length);
                if (Eval.isTrue(Eval.eval(j.on(), new Ctx(combined, java.util.Collections.singletonList(both), false)), "ON", -1)) {
                    out.add(both);
                    matched = true;
                }
            }
            if (!matched && j.left()) {
                out.add(java.util.Arrays.copyOf(l, l.length + rightWidth)); // right side stays null
            }
        }
        return out;
    }

    private List<Out> expandItems(List<Item> items, Schema schema, boolean hasFrom) {
        List<Out> outs = new ArrayList<>();
        for (Item it : items) {
            if (it.star()) {
                if (!hasFrom) {
                    throw new SqlException("* requires a FROM clause", it.pos());
                }
                boolean any = false;
                for (int k = 0; k < schema.size(); k++) {
                    if (it.starQualifier() == null || it.starQualifier().equals(schema.qualifiers().get(k))) {
                        outs.add(new Out(schema.names().get(k), new Col(schema.qualifiers().get(k), schema.names().get(k), it.pos()), null));
                        any = true;
                    }
                }
                if (!any) {
                    throw new SqlException("unknown table " + it.starQualifier(), it.pos());
                }
            } else {
                String label = it.alias();
                if (label == null) {
                    label = it.expr() instanceof Col c ? c.name() : it.text();
                }
                outs.add(new Out(label, it.expr(), it.alias()));
            }
        }
        return outs;
    }

    /** Output column index an ORDER BY key refers to (alias or 1-based position), or -1 for a free expression. */
    private int resolveOrderKey(Expr e, List<Out> outs) {
        if (e instanceof Lit l && l.value() instanceof Long n) {
            if (n < 1 || n > outs.size()) {
                throw new SqlException("ORDER BY position " + n + " is out of range");
            }
            return (int) (n - 1);
        }
        if (e instanceof Col c && c.qualifier() == null) {
            for (int k = 0; k < outs.size(); k++) {
                if (c.name().equals(outs.get(k).alias())) {
                    return k;
                }
            }
        }
        return -1;
    }

    private List<List<Object[]>> group(Select s, List<Object[]> rows, Schema schema, List<Out> outs, int[] outputRef) {
        Set<Integer> groupIdx = new HashSet<>();
        for (Col c : s.groupBy()) {
            groupIdx.add(schema.find(c.qualifier(), c.name(), c.pos()));
        }
        List<Expr> checked = new ArrayList<>();
        outs.forEach(o -> checked.add(o.expr()));
        if (s.having() != null) {
            checked.add(s.having());
        }
        for (int k = 0; k < outputRef.length; k++) {
            if (outputRef[k] < 0) {
                checked.add(s.orderBy().get(k).expr());
            }
        }
        for (Expr e : checked) {
            checkGrouped(e, schema, groupIdx, false);
        }
        Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
        for (Object[] row : rows) {
            List<Object> key = new ArrayList<>();
            for (int idx : groupIdx.stream().sorted().toList()) {
                key.add(row[idx]);
            }
            groups.computeIfAbsent(key, x -> new ArrayList<>()).add(row);
        }
        if (groups.isEmpty() && s.groupBy().isEmpty()) {
            groups.put(List.of(), new ArrayList<>());
        }
        return new ArrayList<>(groups.values());
    }

    private void checkGrouped(Expr e, Schema schema, Set<Integer> groupIdx, boolean insideAggregate) {
        switch (e) {
            case Col c -> {
                int idx = schema.find(c.qualifier(), c.name(), c.pos());
                if (!insideAggregate && !groupIdx.contains(idx)) {
                    throw new SqlException("column " + c.name() + " must appear in GROUP BY or inside an aggregate", c.pos());
                }
            }
            case Call call -> {
                boolean agg = Ast.isAggregateName(call.name());
                if (agg && insideAggregate) {
                    throw new SqlException("aggregate calls cannot be nested", call.pos());
                }
                for (Expr a : call.args()) {
                    checkGrouped(a, schema, groupIdx, insideAggregate || agg);
                }
            }
            case Unary u -> checkGrouped(u.operand(), schema, groupIdx, insideAggregate);
            case Binary b -> {
                checkGrouped(b.left(), schema, groupIdx, insideAggregate);
                checkGrouped(b.right(), schema, groupIdx, insideAggregate);
            }
            case IsNull n -> checkGrouped(n.operand(), schema, groupIdx, insideAggregate);
            case Like k -> {
                checkGrouped(k.operand(), schema, groupIdx, insideAggregate);
                checkGrouped(k.pattern(), schema, groupIdx, insideAggregate);
            }
            case In i -> {
                checkGrouped(i.operand(), schema, groupIdx, insideAggregate);
                i.items().forEach(x -> checkGrouped(x, schema, groupIdx, insideAggregate));
            }
            case Between b -> {
                checkGrouped(b.operand(), schema, groupIdx, insideAggregate);
                checkGrouped(b.low(), schema, groupIdx, insideAggregate);
                checkGrouped(b.high(), schema, groupIdx, insideAggregate);
            }
            case Lit l -> { }
        }
    }

    /** NULL sorts first ascending and last descending; Comparator.sort is stable. */
    private Comparator<Produced> orderComparator(List<OrderKey> order) {
        return (a, b) -> {
            for (int k = 0; k < order.size(); k++) {
                Object x = a.keys().get(k);
                Object y = b.keys().get(k);
                int c;
                if (x == null || y == null) {
                    c = x == null ? (y == null ? 0 : -1) : 1;
                } else {
                    c = Eval.compare(x, y, -1);
                }
                if (c != 0) {
                    return order.get(k).desc() ? -c : c;
                }
            }
            return 0;
        };
    }
}
