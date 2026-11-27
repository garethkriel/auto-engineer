package bench.minisql;

import java.util.*;

/** A tiny in-memory SQL database. */
public final class Database {

    public record Result(List<String> columns, List<List<Object>> rows, int affected) { }

    private final Map<String, Table> tables = new HashMap<>();

    public Database() { }

    public Result execute(String sql) {
        if (sql == null) throw new SqlException("null statement");
        Parser p = new Parser(lex(sql));
        return p.parseStatement();
    }

    // ------------------------------------------------------------ errors
    private static SqlException err(String m) { return new SqlException(m); }

    // ------------------------------------------------------------ lexer
    private enum K { INT, STR, ID, KW, SYM, EOF }

    private record Tok(K kind, String text, long num) { }

    private static final Set<String> RESERVED = Set.of(
        "CREATE", "TABLE", "DROP", "INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE", "GROUP", "BY",
        "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "UPDATE", "SET", "DELETE", "AND", "OR", "NOT",
        "NULL", "IS", "AS", "TRUE", "FALSE", "INT", "TEXT", "COUNT", "SUM", "MIN", "MAX");

    private static boolean letter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') { i++; continue; }
            if (letter(c)) {
                int j = i + 1;
                while (j < n && (letter(s.charAt(j)) || digit(s.charAt(j)))) j++;
                String w = s.substring(i, j);
                String up = w.toUpperCase(Locale.ROOT);
                if (RESERVED.contains(up)) out.add(new Tok(K.KW, up, 0));
                else out.add(new Tok(K.ID, w.toLowerCase(Locale.ROOT), 0));
                i = j;
            } else if (digit(c)) {
                int j = i + 1;
                while (j < n && digit(s.charAt(j))) j++;
                long v;
                try { v = Long.parseLong(s.substring(i, j)); }
                catch (NumberFormatException e) { throw err("integer literal out of range"); }
                out.add(new Tok(K.INT, s.substring(i, j), v));
                i = j;
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\'') {
                        if (j + 1 < n && s.charAt(j + 1) == '\'') { sb.append('\''); j += 2; }
                        else { closed = true; j++; break; }
                    } else { sb.append(d); j++; }
                }
                if (!closed) throw err("unterminated string literal");
                out.add(new Tok(K.STR, sb.toString(), 0));
                i = j;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals("<>") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("||")) {
                    out.add(new Tok(K.SYM, two.equals("!=") ? "<>" : two, 0));
                    i += 2;
                } else if ("(),;*+-/%=<>".indexOf(c) >= 0) {
                    out.add(new Tok(K.SYM, String.valueOf(c), 0));
                    i++;
                } else {
                    throw err("unexpected character '" + c + "'");
                }
            }
        }
        out.add(new Tok(K.EOF, "", 0));
        return out;
    }

    // ------------------------------------------------------------ tables
    private static final class Table {
        final List<String> names = new ArrayList<>();
        final List<Boolean> isInt = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();
        final List<Object[]> rows = new ArrayList<>();
    }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw err("unknown table " + name);
        return t;
    }

    private static Object coerce(Object v, boolean isInt, String col) {
        if (v == null) return null;
        if (isInt ? v instanceof Long : v instanceof String) return v;
        throw err("type mismatch for column " + col);
    }

    // ------------------------------------------------------------ expressions
    private enum Op { OR, AND, EQ, NE, LT, LE, GT, GE, ADD, SUB, MUL, DIV, MOD, CAT }

    private abstract static class Expr {
        abstract Object eval(Object[] row, List<Object[]> grp);
        List<Expr> kids() { return List.of(); }
    }

    private static final class Lit extends Expr {
        final Object v;
        Lit(Object v) { this.v = v; }
        Object eval(Object[] row, List<Object[]> grp) { return v; }
    }

    private static final class Col extends Expr {
        final String name;
        int idx = -1;
        Col(String name) { this.name = name; }
        Object eval(Object[] row, List<Object[]> grp) { return row[idx]; }
    }

    private static final class Neg extends Expr {
        final Expr e;
        Neg(Expr e) { this.e = e; }
        Object eval(Object[] row, List<Object[]> grp) {
            Object v = e.eval(row, grp);
            if (v == null) return null;
            if (!(v instanceof Long l)) throw err("unary minus needs INT");
            return -l;
        }
        List<Expr> kids() { return List.of(e); }
    }

    private static final class Not extends Expr {
        final Expr e;
        Not(Expr e) { this.e = e; }
        Object eval(Object[] row, List<Object[]> grp) {
            Object v = e.eval(row, grp);
            if (v == null) return null;
            if (!(v instanceof Boolean b)) throw err("NOT needs boolean");
            return !b;
        }
        List<Expr> kids() { return List.of(e); }
    }

    private static final class IsNull extends Expr {
        final Expr e;
        final boolean negated;
        IsNull(Expr e, boolean negated) { this.e = e; this.negated = negated; }
        Object eval(Object[] row, List<Object[]> grp) {
            boolean isNull = e.eval(row, grp) == null;
            return negated != isNull;
        }
        List<Expr> kids() { return List.of(e); }
    }

    private static final class Bin extends Expr {
        final Op op;
        final Expr l, r;
        Bin(Op op, Expr l, Expr r) { this.op = op; this.l = l; this.r = r; }
        List<Expr> kids() { return List.of(l, r); }

        Object eval(Object[] row, List<Object[]> grp) {
            Object a = l.eval(row, grp);
            Object b = r.eval(row, grp);
            switch (op) {
                case AND, OR -> {
                    if (a != null && !(a instanceof Boolean)) throw err("boolean expected");
                    if (b != null && !(b instanceof Boolean)) throw err("boolean expected");
                    Boolean x = (Boolean) a, y = (Boolean) b;
                    if (op == Op.AND) {
                        if (Boolean.FALSE.equals(x) || Boolean.FALSE.equals(y)) return false;
                        if (x == null || y == null) return null;
                        return true;
                    }
                    if (Boolean.TRUE.equals(x) || Boolean.TRUE.equals(y)) return true;
                    if (x == null || y == null) return null;
                    return false;
                }
                case ADD, SUB, MUL, DIV, MOD -> {
                    if (a != null && !(a instanceof Long)) throw err("INT expected");
                    if (b != null && !(b instanceof Long)) throw err("INT expected");
                    if (a == null || b == null) return null;
                    long x = (Long) a, y = (Long) b;
                    switch (op) {
                        case ADD: return x + y;
                        case SUB: return x - y;
                        case MUL: return x * y;
                        case DIV:
                            if (y == 0) throw err("division by zero");
                            return x / y;
                        default:
                            if (y == 0) throw err("division by zero");
                            return x % y;
                    }
                }
                case CAT -> {
                    if (a != null && !(a instanceof String)) throw err("TEXT expected");
                    if (b != null && !(b instanceof String)) throw err("TEXT expected");
                    if (a == null || b == null) return null;
                    return (String) a + (String) b;
                }
                default -> {
                    if (a == null || b == null) return null;
                    int c = cmp(a, b);
                    return switch (op) {
                        case EQ -> c == 0;
                        case NE -> c != 0;
                        case LT -> c < 0;
                        case LE -> c <= 0;
                        case GT -> c > 0;
                        default -> c >= 0;
                    };
                }
            }
        }
    }

    /** Compares two non-null values of the same type, else throws. */
    private static int cmp(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) return Long.compare(x, y);
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return Boolean.compare(x, y);
        throw err("cannot compare values of different types");
    }

    private static final class Agg extends Expr {
        final String fn; // COUNT SUM MIN MAX
        final Expr arg;  // null for COUNT(*)
        Agg(String fn, Expr arg) { this.fn = fn; this.arg = arg; }
        List<Expr> kids() { return arg == null ? List.of() : List.of(arg); }

        Object eval(Object[] row, List<Object[]> grp) {
            if (grp == null) throw err("aggregate not allowed here");
            if (arg == null) return (long) grp.size();
            long count = 0;
            long sum = 0;
            Object best = null;
            for (Object[] r : grp) {
                Object v = arg.eval(r, null);
                if (v == null) continue;
                count++;
                switch (fn) {
                    case "SUM" -> {
                        if (!(v instanceof Long l)) throw err("SUM needs INT");
                        sum += l;
                    }
                    case "MIN", "MAX" -> {
                        if (!(v instanceof Long) && !(v instanceof String)) throw err(fn + " needs INT or TEXT");
                        if (best == null) best = v;
                        else {
                            int c = cmp(v, best);
                            if (fn.equals("MIN") ? c < 0 : c > 0) best = v;
                        }
                    }
                    default -> { }
                }
            }
            return switch (fn) {
                case "COUNT" -> count;
                case "SUM" -> count == 0 ? null : (Object) sum;
                default -> best;
            };
        }
    }

    private static boolean hasAgg(Expr e) {
        if (e instanceof Agg) return true;
        for (Expr k : e.kids()) if (hasAgg(k)) return true;
        return false;
    }

    /**
     * Resolves columns of e against t (t may be null: columns not allowed) and, when grouped is
     * non-null, checks the aggregate-query column rules.
     */
    private static void bind(Expr e, Table t, Set<Integer> grouped, boolean inAgg) {
        if (e instanceof Col c) {
            if (t == null) throw err("column reference not allowed here: " + c.name);
            Integer i = t.index.get(c.name);
            if (i == null) throw err("unknown column " + c.name);
            c.idx = i;
            if (grouped != null && !inAgg && !grouped.contains(i))
                throw err("column " + c.name + " must be grouped or aggregated");
            return;
        }
        if (e instanceof Agg a) {
            if (inAgg) throw err("nested aggregate");
            if (a.arg != null) bind(a.arg, t, grouped, true);
            return;
        }
        for (Expr k : e.kids()) bind(k, t, grouped, inAgg);
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw err("condition must be boolean");
    }

    // ------------------------------------------------------------ parser + executor
    private record Item(Expr e, String alias) { }
    private record Order(Expr e, boolean desc) { }

    private final class Parser {
        final List<Tok> toks;
        int pos = 0;

        Parser(List<Tok> toks) { this.toks = toks; }

        Tok peek() { return toks.get(pos); }
        Tok next() { Tok t = toks.get(pos); if (t.kind != K.EOF) pos++; return t; }
        boolean isKw(String k) { Tok t = peek(); return t.kind == K.KW && t.text.equals(k); }
        boolean isSym(String s) { Tok t = peek(); return t.kind == K.SYM && t.text.equals(s); }
        boolean acceptKw(String k) { if (isKw(k)) { pos++; return true; } return false; }
        boolean acceptSym(String s) { if (isSym(s)) { pos++; return true; } return false; }
        void expectKw(String k) { if (!acceptKw(k)) throw err("expected " + k + " near '" + peek().text + "'"); }
        void expectSym(String s) { if (!acceptSym(s)) throw err("expected " + s + " near '" + peek().text + "'"); }
        String ident() {
            Tok t = peek();
            if (t.kind != K.ID) throw err("identifier expected near '" + t.text + "'");
            pos++;
            return t.text;
        }
        long intLit() {
            Tok t = peek();
            if (t.kind != K.INT) throw err("integer expected near '" + t.text + "'");
            pos++;
            return t.num;
        }
        void end() {
            acceptSym(";");
            if (peek().kind != K.EOF) throw err("unexpected input near '" + peek().text + "'");
        }

        Result parseStatement() {
            Tok t = peek();
            if (t.kind != K.KW) throw err("statement expected");
            switch (t.text) {
                case "CREATE": return create();
                case "DROP": return drop();
                case "INSERT": return insert();
                case "SELECT": return select();
                case "UPDATE": return update();
                case "DELETE": return delete();
                default: throw err("unknown statement " + t.text);
            }
        }

        Result empty(int affected) { return new Result(List.of(), List.of(), affected); }

        // ---- DDL
        Result create() {
            expectKw("CREATE"); expectKw("TABLE");
            String name = ident();
            expectSym("(");
            Table t = new Table();
            do {
                String col = ident();
                boolean isInt;
                if (acceptKw("INT")) isInt = true;
                else if (acceptKw("TEXT")) isInt = false;
                else throw err("type expected");
                if (t.index.containsKey(col)) throw err("duplicate column " + col);
                t.index.put(col, t.names.size());
                t.names.add(col);
                t.isInt.add(isInt);
            } while (acceptSym(","));
            expectSym(")");
            end();
            if (tables.containsKey(name)) throw err("table exists: " + name);
            tables.put(name, t);
            return empty(0);
        }

        Result drop() {
            expectKw("DROP"); expectKw("TABLE");
            String name = ident();
            end();
            table(name);
            tables.remove(name);
            return empty(0);
        }

        // ---- INSERT
        Result insert() {
            expectKw("INSERT"); expectKw("INTO");
            String name = ident();
            List<String> cols = null;
            if (acceptSym("(")) {
                cols = new ArrayList<>();
                do { cols.add(ident()); } while (acceptSym(","));
                expectSym(")");
            }
            expectKw("VALUES");
            List<List<Expr>> tuples = new ArrayList<>();
            do {
                expectSym("(");
                List<Expr> vs = new ArrayList<>();
                do { vs.add(expr()); } while (acceptSym(","));
                expectSym(")");
                tuples.add(vs);
            } while (acceptSym(","));
            end();
            Table t = table(name);
            int[] target;
            if (cols == null) {
                target = new int[t.names.size()];
                for (int i = 0; i < target.length; i++) target[i] = i;
            } else {
                target = new int[cols.size()];
                Set<Integer> seen = new HashSet<>();
                for (int i = 0; i < target.length; i++) {
                    Integer ix = t.index.get(cols.get(i));
                    if (ix == null) throw err("unknown column " + cols.get(i));
                    if (!seen.add(ix)) throw err("duplicate column " + cols.get(i));
                    target[i] = ix;
                }
            }
            for (List<Expr> vs : tuples) {
                if (vs.size() != target.length) throw err("value count mismatch");
                for (Expr e : vs) {
                    if (hasAgg(e)) throw err("aggregate not allowed in INSERT");
                    bind(e, null, null, false);
                }
            }
            List<Object[]> newRows = new ArrayList<>(tuples.size());
            for (List<Expr> vs : tuples) {
                Object[] row = new Object[t.names.size()];
                for (int i = 0; i < target.length; i++) {
                    Object v = vs.get(i).eval(null, null);
                    row[target[i]] = coerce(v, t.isInt.get(target[i]), t.names.get(target[i]));
                }
                newRows.add(row);
            }
            t.rows.addAll(newRows);
            return empty(newRows.size());
        }

        // ---- UPDATE / DELETE
        Result update() {
            expectKw("UPDATE");
            String name = ident();
            expectKw("SET");
            List<String> cols = new ArrayList<>();
            List<Expr> exprs = new ArrayList<>();
            do {
                cols.add(ident());
                expectSym("=");
                exprs.add(expr());
            } while (acceptSym(","));
            Expr where = acceptKw("WHERE") ? expr() : null;
            end();
            Table t = table(name);
            int[] idx = new int[cols.size()];
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < idx.length; i++) {
                Integer ix = t.index.get(cols.get(i));
                if (ix == null) throw err("unknown column " + cols.get(i));
                if (!seen.add(ix)) throw err("column set twice: " + cols.get(i));
                idx[i] = ix;
            }
            for (Expr e : exprs) {
                if (hasAgg(e)) throw err("aggregate not allowed in UPDATE");
                bind(e, t, null, false);
            }
            if (where != null) {
                if (hasAgg(where)) throw err("aggregate not allowed in WHERE");
                bind(where, t, null, false);
            }
            int n = t.rows.size();
            Object[][] updated = new Object[n][];
            int affected = 0;
            for (int r = 0; r < n; r++) {
                Object[] row = t.rows.get(r);
                if (where != null && !truthy(where.eval(row, null))) continue;
                Object[] nr = row.clone();
                for (int i = 0; i < idx.length; i++)
                    nr[idx[i]] = coerce(exprs.get(i).eval(row, null), t.isInt.get(idx[i]), cols.get(i));
                updated[r] = nr;
                affected++;
            }
            for (int r = 0; r < n; r++) if (updated[r] != null) t.rows.set(r, updated[r]);
            return empty(affected);
        }

        Result delete() {
            expectKw("DELETE"); expectKw("FROM");
            String name = ident();
            Expr where = acceptKw("WHERE") ? expr() : null;
            end();
            Table t = table(name);
            if (where == null) {
                int n = t.rows.size();
                t.rows.clear();
                return empty(n);
            }
            if (hasAgg(where)) throw err("aggregate not allowed in WHERE");
            bind(where, t, null, false);
            List<Object[]> keep = new ArrayList<>();
            int deleted = 0;
            for (Object[] row : t.rows) {
                if (truthy(where.eval(row, null))) deleted++; else keep.add(row);
            }
            t.rows.clear();
            t.rows.addAll(keep);
            return empty(deleted);
        }

        // ---- SELECT
        Result select() {
            expectKw("SELECT");
            boolean star = false;
            List<Item> items = new ArrayList<>();
            if (acceptSym("*")) star = true;
            else {
                do {
                    Expr e = expr();
                    String alias = acceptKw("AS") ? ident() : null;
                    items.add(new Item(e, alias));
                } while (acceptSym(","));
            }
            expectKw("FROM");
            String name = ident();
            Expr where = acceptKw("WHERE") ? expr() : null;
            List<String> groupBy = null;
            if (acceptKw("GROUP")) {
                expectKw("BY");
                groupBy = new ArrayList<>();
                do { groupBy.add(ident()); } while (acceptSym(","));
            }
            Expr having = acceptKw("HAVING") ? expr() : null;
            List<Order> order = new ArrayList<>();
            if (acceptKw("ORDER")) {
                expectKw("BY");
                do {
                    Expr e = expr();
                    boolean desc = false;
                    if (acceptKw("DESC")) desc = true; else acceptKw("ASC");
                    order.add(new Order(e, desc));
                } while (acceptSym(","));
            }
            long limit = -1, offset = 0;
            if (acceptKw("LIMIT")) {
                limit = intLit();
                if (acceptKw("OFFSET")) offset = intLit();
            }
            end();
            return runSelect(star, items, name, where, groupBy, having, order, limit, offset);
        }

        Result runSelect(boolean star, List<Item> items, String name, Expr where, List<String> groupBy,
                         Expr having, List<Order> order, long limit, long offset) {
            Table t = table(name);

            // output names
            List<String> outNames = new ArrayList<>();
            if (star) outNames.addAll(t.names);
            else {
                for (int i = 0; i < items.size(); i++) {
                    Item it = items.get(i);
                    if (it.alias != null) outNames.add(it.alias);
                    else if (it.e instanceof Col c) outNames.add(c.name);
                    else outNames.add("col" + (i + 1));
                }
            }

            // order items: ordinal (>=0) or expression
            int[] ordinal = new int[order.size()];
            Expr[] orderExpr = new Expr[order.size()];
            for (int i = 0; i < order.size(); i++) {
                Expr e = order.get(i).e;
                ordinal[i] = -1;
                if (e instanceof Lit l && l.v instanceof Long k) {
                    if (k < 1 || k > outNames.size()) throw err("ORDER BY position out of range");
                    ordinal[i] = (int) (k - 1);
                } else if (e instanceof Col c && outNames.contains(c.name)) {
                    ordinal[i] = outNames.indexOf(c.name);
                } else {
                    orderExpr[i] = e;
                }
            }

            boolean agg = groupBy != null || having != null;
            for (Item it : items) if (hasAgg(it.e)) agg = true;
            for (Expr e : orderExpr) if (e != null && hasAgg(e)) agg = true;

            if (where != null) {
                if (hasAgg(where)) throw err("aggregate not allowed in WHERE");
                bind(where, t, null, false);
            }

            Set<Integer> grouped = null;
            int[] groupIdx = new int[0];
            if (agg) {
                if (star) throw err("* not allowed in aggregate query");
                grouped = new HashSet<>();
                if (groupBy != null) {
                    groupIdx = new int[groupBy.size()];
                    for (int i = 0; i < groupIdx.length; i++) {
                        Integer ix = t.index.get(groupBy.get(i));
                        if (ix == null) throw err("unknown column " + groupBy.get(i));
                        groupIdx[i] = ix;
                        grouped.add(ix);
                    }
                }
            }
            for (Item it : items) bind(it.e, t, grouped, false);
            if (having != null) bind(having, t, grouped, false);
            for (Expr e : orderExpr) if (e != null) bind(e, t, grouped, false);

            // filter
            List<Object[]> rows;
            if (where == null) rows = t.rows;
            else {
                rows = new ArrayList<>();
                for (Object[] r : t.rows) if (truthy(where.eval(r, null))) rows.add(r);
            }

            int nOut = outNames.size();
            List<Object[][]> produced = new ArrayList<>(); // each: {out, keys}
            if (!agg) {
                for (Object[] r : rows) {
                    Object[] out;
                    if (star) out = r.clone();
                    else {
                        out = new Object[nOut];
                        for (int i = 0; i < nOut; i++) out[i] = items.get(i).e.eval(r, null);
                    }
                    Object[] keys = new Object[order.size()];
                    for (int i = 0; i < keys.length; i++)
                        keys[i] = ordinal[i] >= 0 ? out[ordinal[i]] : orderExpr[i].eval(r, null);
                    produced.add(new Object[][] {out, keys});
                }
            } else {
                Collection<List<Object[]>> groups;
                if (groupIdx.length == 0) {
                    groups = List.of(rows);
                } else {
                    Map<List<Object>, List<Object[]>> m = new LinkedHashMap<>();
                    for (Object[] r : rows) {
                        List<Object> key = new ArrayList<>(groupIdx.length);
                        for (int gi : groupIdx) key.add(r[gi]);
                        m.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                    }
                    groups = m.values();
                }
                for (List<Object[]> g : groups) {
                    Object[] rep = g.isEmpty() ? null : g.get(0);
                    if (having != null && !truthy(having.eval(rep, g))) continue;
                    Object[] out = new Object[nOut];
                    for (int i = 0; i < nOut; i++) out[i] = items.get(i).e.eval(rep, g);
                    Object[] keys = new Object[order.size()];
                    for (int i = 0; i < keys.length; i++)
                        keys[i] = ordinal[i] >= 0 ? out[ordinal[i]] : orderExpr[i].eval(rep, g);
                    produced.add(new Object[][] {out, keys});
                }
            }

            if (!order.isEmpty()) {
                boolean[] desc = new boolean[order.size()];
                for (int i = 0; i < desc.length; i++) desc[i] = order.get(i).desc;
                produced.sort((x, y) -> {
                    for (int i = 0; i < desc.length; i++) {
                        Object a = x[1][i], b = y[1][i];
                        int c;
                        if (a == null || b == null) c = a == null ? (b == null ? 0 : -1) : 1;
                        else c = cmp(a, b);
                        if (c != 0) return desc[i] ? -c : c;
                    }
                    return 0;
                });
            }

            List<List<Object>> result = new ArrayList<>();
            long skipped = 0;
            for (Object[][] p : produced) {
                if (skipped < offset) { skipped++; continue; }
                if (limit >= 0 && result.size() >= limit) break;
                result.add(Collections.unmodifiableList(new ArrayList<>(Arrays.asList(p[0]))));
            }
            return new Result(Collections.unmodifiableList(new ArrayList<>(outNames)),
                Collections.unmodifiableList(result), 0);
        }

        // ---- expressions
        Expr expr() {
            Expr l = and();
            while (acceptKw("OR")) l = new Bin(Op.OR, l, and());
            return l;
        }

        Expr and() {
            Expr l = not();
            while (acceptKw("AND")) l = new Bin(Op.AND, l, not());
            return l;
        }

        Expr not() {
            if (acceptKw("NOT")) return new Not(not());
            return cmpExpr();
        }

        Expr cmpExpr() {
            Expr l = add();
            Tok t = peek();
            if (t.kind == K.SYM) {
                Op op = switch (t.text) {
                    case "=" -> Op.EQ;
                    case "<>" -> Op.NE;
                    case "<" -> Op.LT;
                    case "<=" -> Op.LE;
                    case ">" -> Op.GT;
                    case ">=" -> Op.GE;
                    default -> null;
                };
                if (op != null) {
                    pos++;
                    return new Bin(op, l, add());
                }
            } else if (isKw("IS")) {
                pos++;
                boolean neg = acceptKw("NOT");
                expectKw("NULL");
                return new IsNull(l, neg);
            }
            return l;
        }

        Expr add() {
            Expr l = mul();
            while (true) {
                if (acceptSym("+")) l = new Bin(Op.ADD, l, mul());
                else if (acceptSym("-")) l = new Bin(Op.SUB, l, mul());
                else if (acceptSym("||")) l = new Bin(Op.CAT, l, mul());
                else return l;
            }
        }

        Expr mul() {
            Expr l = unary();
            while (true) {
                if (acceptSym("*")) l = new Bin(Op.MUL, l, unary());
                else if (acceptSym("/")) l = new Bin(Op.DIV, l, unary());
                else if (acceptSym("%")) l = new Bin(Op.MOD, l, unary());
                else return l;
            }
        }

        Expr unary() {
            if (acceptSym("-")) return new Neg(unary());
            return primary();
        }

        Expr primary() {
            Tok t = peek();
            switch (t.kind) {
                case INT: pos++; return new Lit(t.num);
                case STR: pos++; return new Lit(t.text);
                case ID: pos++; return new Col(t.text);
                case SYM:
                    if (t.text.equals("(")) {
                        pos++;
                        Expr e = expr();
                        expectSym(")");
                        return e;
                    }
                    throw err("unexpected '" + t.text + "'");
                case KW:
                    switch (t.text) {
                        case "NULL": pos++; return new Lit(null);
                        case "TRUE": pos++; return new Lit(Boolean.TRUE);
                        case "FALSE": pos++; return new Lit(Boolean.FALSE);
                        case "COUNT": case "SUM": case "MIN": case "MAX": {
                            pos++;
                            expectSym("(");
                            Expr arg;
                            if (t.text.equals("COUNT") && acceptSym("*")) arg = null;
                            else arg = expr();
                            expectSym(")");
                            return new Agg(t.text, arg);
                        }
                        default: throw err("unexpected keyword " + t.text);
                    }
                default:
                    throw err("unexpected end of statement");
            }
        }
    }
}
