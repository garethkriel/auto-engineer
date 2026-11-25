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

/** A small in-memory SQL database. */
public final class Database {
    public record Result(List<String> columns, List<List<Object>> rows, int affected) {}

    // ------------------------------------------------------------------ lexer

    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "CREATE", "TABLE", "DROP", "INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE", "GROUP", "BY",
            "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "UPDATE", "SET", "DELETE", "AND", "OR", "NOT",
            "NULL", "IS", "AS", "TRUE", "FALSE", "INT", "TEXT", "COUNT", "SUM", "MIN", "MAX"));

    private static final int EOF = 0;
    private static final int KW = 1;
    private static final int ID = 2;
    private static final int INT = 3;
    private static final int STR = 4;
    private static final int SYM = 5;

    private record Tok(int type, String text, long num) {}

    private static SqlException err(String m) {
        return new SqlException(m);
    }

    private static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else if (isLetter(c)) {
                int j = i;
                while (j < n && (isLetter(s.charAt(j)) || isDigit(s.charAt(j)))) j++;
                String w = s.substring(i, j);
                String up = w.toUpperCase(java.util.Locale.ROOT);
                if (RESERVED.contains(up)) out.add(new Tok(KW, up, 0));
                else out.add(new Tok(ID, w.toLowerCase(java.util.Locale.ROOT), 0));
                i = j;
            } else if (isDigit(c)) {
                int j = i;
                while (j < n && isDigit(s.charAt(j))) j++;
                long v;
                try {
                    v = Long.parseLong(s.substring(i, j));
                } catch (NumberFormatException e) {
                    throw err("integer literal out of range");
                }
                out.add(new Tok(INT, s.substring(i, j), v));
                i = j;
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\'') {
                        if (j + 1 < n && s.charAt(j + 1) == '\'') {
                            sb.append('\'');
                            j += 2;
                        } else {
                            closed = true;
                            j++;
                            break;
                        }
                    } else {
                        sb.append(d);
                        j++;
                    }
                }
                if (!closed) throw err("unterminated string");
                out.add(new Tok(STR, sb.toString(), 0));
                i = j;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals("<>") || two.equals("<=") || two.equals(">=") || two.equals("||")) {
                    out.add(new Tok(SYM, two, 0));
                    i += 2;
                } else if (two.equals("!=")) {
                    out.add(new Tok(SYM, "<>", 0));
                    i += 2;
                } else if ("(),;*+-/%=<>".indexOf(c) >= 0) {
                    out.add(new Tok(SYM, String.valueOf(c), 0));
                    i++;
                } else {
                    throw err("unexpected character '" + c + "'");
                }
            }
        }
        out.add(new Tok(EOF, "", 0));
        return out;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // -------------------------------------------------------------------- AST

    private sealed interface Expr permits Lit, Col, Neg, Not, IsNull, Bin, Agg {}

    private record Lit(Object v) implements Expr {}

    private record Col(String name) implements Expr {}

    private record Neg(Expr e) implements Expr {}

    private record Not(Expr e) implements Expr {}

    private record IsNull(Expr e, boolean negated) implements Expr {}

    private record Bin(String op, Expr l, Expr r) implements Expr {}

    /** fn is COUNT, SUM, MIN or MAX; arg null means COUNT(*). */
    private record Agg(String fn, Expr arg) implements Expr {}

    private record Item(Expr expr, String alias) {}

    private static final int O_EXPR = 0;
    private static final int O_INDEX = 1;
    private static final int O_NAME = 2;

    private record OrderItem(int kind, Expr expr, long index, String name, boolean desc) {}

    // ----------------------------------------------------------------- parser

    private static final class Parser {
        private final List<Tok> t;
        private int p;

        Parser(List<Tok> t) {
            this.t = t;
        }

        Tok peek() {
            return t.get(p);
        }

        Tok peekAt(int k) {
            return t.get(Math.min(p + k, t.size() - 1));
        }

        Tok next() {
            Tok x = t.get(p);
            if (x.type != EOF) p++;
            return x;
        }

        boolean isKw(String k) {
            Tok x = peek();
            return x.type == KW && x.text.equals(k);
        }

        boolean isSym(String s) {
            Tok x = peek();
            return x.type == SYM && x.text.equals(s);
        }

        boolean acceptKw(String k) {
            if (isKw(k)) {
                p++;
                return true;
            }
            return false;
        }

        boolean acceptSym(String s) {
            if (isSym(s)) {
                p++;
                return true;
            }
            return false;
        }

        void expectKw(String k) {
            if (!acceptKw(k)) throw err("expected " + k + " but found '" + peek().text + "'");
        }

        void expectSym(String s) {
            if (!acceptSym(s)) throw err("expected '" + s + "' but found '" + peek().text + "'");
        }

        String ident() {
            Tok x = peek();
            if (x.type != ID) throw err("expected identifier but found '" + x.text + "'");
            p++;
            return x.text;
        }

        void end() {
            acceptSym(";");
            if (peek().type != EOF) throw err("unexpected '" + peek().text + "'");
        }

        // expressions
        Expr expr() {
            Expr l = and();
            while (acceptKw("OR")) l = new Bin("OR", l, and());
            return l;
        }

        Expr and() {
            Expr l = not();
            while (acceptKw("AND")) l = new Bin("AND", l, not());
            return l;
        }

        Expr not() {
            if (acceptKw("NOT")) return new Not(not());
            return cmp();
        }

        Expr cmp() {
            Expr l = add();
            Tok x = peek();
            if (x.type == SYM) {
                switch (x.text) {
                    case "=", "<>", "<", "<=", ">", ">=" -> {
                        p++;
                        return new Bin(x.text, l, add());
                    }
                    default -> { }
                }
            } else if (isKw("IS")) {
                p++;
                boolean neg = acceptKw("NOT");
                expectKw("NULL");
                return new IsNull(l, neg);
            }
            return l;
        }

        Expr add() {
            Expr l = mul();
            while (true) {
                Tok x = peek();
                if (x.type == SYM && (x.text.equals("+") || x.text.equals("-") || x.text.equals("||"))) {
                    p++;
                    l = new Bin(x.text, l, mul());
                } else {
                    return l;
                }
            }
        }

        Expr mul() {
            Expr l = unary();
            while (true) {
                Tok x = peek();
                if (x.type == SYM && (x.text.equals("*") || x.text.equals("/") || x.text.equals("%"))) {
                    p++;
                    l = new Bin(x.text, l, unary());
                } else {
                    return l;
                }
            }
        }

        Expr unary() {
            if (acceptSym("-")) return new Neg(unary());
            return primary();
        }

        Expr primary() {
            Tok x = peek();
            switch (x.type) {
                case INT -> {
                    p++;
                    return new Lit(x.num);
                }
                case STR -> {
                    p++;
                    return new Lit(x.text);
                }
                case ID -> {
                    p++;
                    return new Col(x.text);
                }
                case KW -> {
                    switch (x.text) {
                        case "NULL" -> {
                            p++;
                            return new Lit(null);
                        }
                        case "TRUE" -> {
                            p++;
                            return new Lit(Boolean.TRUE);
                        }
                        case "FALSE" -> {
                            p++;
                            return new Lit(Boolean.FALSE);
                        }
                        case "COUNT", "SUM", "MIN", "MAX" -> {
                            p++;
                            expectSym("(");
                            Expr arg;
                            if (x.text.equals("COUNT") && acceptSym("*")) {
                                arg = null;
                            } else {
                                arg = expr();
                            }
                            expectSym(")");
                            return new Agg(x.text, arg);
                        }
                        default -> throw err("unexpected keyword " + x.text);
                    }
                }
                case SYM -> {
                    if (x.text.equals("(")) {
                        p++;
                        Expr e = expr();
                        expectSym(")");
                        return e;
                    }
                    throw err("unexpected '" + x.text + "'");
                }
                default -> throw err("unexpected end of statement");
            }
        }
    }

    // ------------------------------------------------------------- statements

    private static final class Table {
        final String name;
        final List<String> cols = new ArrayList<>();
        final List<Boolean> isInt = new ArrayList<>();
        final Map<String, Integer> idx = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();

        Table(String name) {
            this.name = name;
        }
    }

    private final Map<String, Table> tables = new HashMap<>();

    public Database() {}

    public synchronized Result execute(String sql) {
        if (sql == null) throw err("null statement");
        Parser ps = new Parser(lex(sql));
        Tok first = ps.peek();
        if (first.type != KW) throw err("expected statement");
        return switch (first.text) {
            case "CREATE" -> create(ps);
            case "DROP" -> drop(ps);
            case "INSERT" -> insert(ps);
            case "UPDATE" -> update(ps);
            case "DELETE" -> delete(ps);
            case "SELECT" -> select(ps);
            default -> throw err("unknown statement " + first.text);
        };
    }

    private static Result affected(int n) {
        return new Result(List.of(), List.of(), n);
    }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw err("unknown table " + name);
        return t;
    }

    private Result create(Parser ps) {
        ps.next();
        ps.expectKw("TABLE");
        String name = ps.ident();
        ps.expectSym("(");
        Table t = new Table(name);
        do {
            String c = ps.ident();
            boolean isInt;
            if (ps.acceptKw("INT")) isInt = true;
            else if (ps.acceptKw("TEXT")) isInt = false;
            else throw err("expected column type");
            if (t.idx.containsKey(c)) throw err("duplicate column " + c);
            t.idx.put(c, t.cols.size());
            t.cols.add(c);
            t.isInt.add(isInt);
        } while (ps.acceptSym(","));
        ps.expectSym(")");
        ps.end();
        if (tables.containsKey(name)) throw err("table exists " + name);
        tables.put(name, t);
        return affected(0);
    }

    private Result drop(Parser ps) {
        ps.next();
        ps.expectKw("TABLE");
        String name = ps.ident();
        ps.end();
        table(name);
        tables.remove(name);
        return affected(0);
    }

    private Result insert(Parser ps) {
        ps.next();
        ps.expectKw("INTO");
        Table t = table(ps.ident());
        List<Integer> target = new ArrayList<>();
        if (ps.acceptSym("(")) {
            Set<String> seen = new HashSet<>();
            do {
                String c = ps.ident();
                Integer ix = t.idx.get(c);
                if (ix == null) throw err("unknown column " + c);
                if (!seen.add(c)) throw err("duplicate column " + c);
                target.add(ix);
            } while (ps.acceptSym(","));
            ps.expectSym(")");
        } else {
            for (int i = 0; i < t.cols.size(); i++) target.add(i);
        }
        ps.expectKw("VALUES");
        List<List<Expr>> tuples = new ArrayList<>();
        do {
            ps.expectSym("(");
            List<Expr> vs = new ArrayList<>();
            do {
                Expr e = ps.expr();
                check(e, null, null, false, false);
                vs.add(e);
            } while (ps.acceptSym(","));
            ps.expectSym(")");
            if (vs.size() != target.size()) throw err("value count mismatch");
            tuples.add(vs);
        } while (ps.acceptSym(","));
        ps.end();
        List<Object[]> add = new ArrayList<>(tuples.size());
        for (List<Expr> vs : tuples) {
            Object[] row = new Object[t.cols.size()];
            for (int i = 0; i < vs.size(); i++) {
                Object v = eval(vs.get(i), NO_ENV);
                int ci = target.get(i);
                checkStorable(t, ci, v);
                row[ci] = v;
            }
            add.add(row);
        }
        t.rows.addAll(add);
        return affected(add.size());
    }

    private static void checkStorable(Table t, int ci, Object v) {
        if (v == null) return;
        if (t.isInt.get(ci)) {
            if (!(v instanceof Long)) throw err("type mismatch for column " + t.cols.get(ci));
        } else if (!(v instanceof String)) {
            throw err("type mismatch for column " + t.cols.get(ci));
        }
    }

    private Result update(Parser ps) {
        ps.next();
        Table t = table(ps.ident());
        ps.expectKw("SET");
        List<Integer> cols = new ArrayList<>();
        List<Expr> exprs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        do {
            String c = ps.ident();
            Integer ix = t.idx.get(c);
            if (ix == null) throw err("unknown column " + c);
            if (!seen.add(c)) throw err("column set twice " + c);
            ps.expectSym("=");
            Expr e = ps.expr();
            check(e, t, null, false, false);
            cols.add(ix);
            exprs.add(e);
        } while (ps.acceptSym(","));
        Expr where = null;
        if (ps.acceptKw("WHERE")) {
            where = ps.expr();
            check(where, t, null, false, false);
        }
        ps.end();
        List<Object[]> newRows = new ArrayList<>(t.rows.size());
        int count = 0;
        for (Object[] row : t.rows) {
            RowEnv env = new RowEnv(t, row);
            if (where == null || cond(eval(where, env))) {
                Object[] nr = row.clone();
                for (int i = 0; i < cols.size(); i++) {
                    Object v = eval(exprs.get(i), env);
                    checkStorable(t, cols.get(i), v);
                    nr[cols.get(i)] = v;
                }
                newRows.add(nr);
                count++;
            } else {
                newRows.add(row);
            }
        }
        t.rows = newRows;
        return affected(count);
    }

    private Result delete(Parser ps) {
        ps.next();
        ps.expectKw("FROM");
        Table t = table(ps.ident());
        Expr where = null;
        if (ps.acceptKw("WHERE")) {
            where = ps.expr();
            check(where, t, null, false, false);
        }
        ps.end();
        List<Object[]> keep = new ArrayList<>();
        int count = 0;
        for (Object[] row : t.rows) {
            if (where == null || cond(eval(where, new RowEnv(t, row)))) count++;
            else keep.add(row);
        }
        t.rows = keep;
        return affected(count);
    }

    // ----------------------------------------------------------------- select

    private Result select(Parser ps) {
        ps.next();
        boolean star = false;
        List<Item> items = new ArrayList<>();
        if (ps.acceptSym("*")) {
            star = true;
        } else {
            do {
                Expr e = ps.expr();
                String alias = null;
                if (ps.acceptKw("AS")) alias = ps.ident();
                items.add(new Item(e, alias));
            } while (ps.acceptSym(","));
        }
        ps.expectKw("FROM");
        Table t = table(ps.ident());
        Expr where = null;
        if (ps.acceptKw("WHERE")) where = ps.expr();
        List<String> groupBy = null;
        if (ps.acceptKw("GROUP")) {
            ps.expectKw("BY");
            groupBy = new ArrayList<>();
            do {
                groupBy.add(ps.ident());
            } while (ps.acceptSym(","));
        }
        Expr having = null;
        if (ps.acceptKw("HAVING")) having = ps.expr();
        List<OrderItem> order = new ArrayList<>();
        if (ps.acceptKw("ORDER")) {
            ps.expectKw("BY");
            do {
                order.add(orderItem(ps));
            } while (ps.acceptSym(","));
        }
        long limit = -1;
        long offset = 0;
        if (ps.acceptKw("LIMIT")) {
            Tok x = ps.next();
            if (x.type != INT) throw err("LIMIT needs an integer");
            limit = x.num;
            if (ps.acceptKw("OFFSET")) {
                Tok y = ps.next();
                if (y.type != INT) throw err("OFFSET needs an integer");
                offset = y.num;
            }
        }
        ps.end();

        // output columns
        List<String> outNames = new ArrayList<>();
        List<Expr> outExprs = new ArrayList<>();
        boolean aggregate = groupBy != null || having != null;
        if (star) {
            for (String c : t.cols) {
                outNames.add(c);
                outExprs.add(new Col(c));
            }
        } else {
            int pos = 1;
            for (Item it : items) {
                String name = it.alias != null ? it.alias
                        : it.expr instanceof Col c ? c.name : "col" + pos;
                outNames.add(name);
                outExprs.add(it.expr);
                if (hasAgg(it.expr)) aggregate = true;
                pos++;
            }
        }
        for (OrderItem o : order) {
            if (o.kind == O_EXPR && hasAgg(o.expr)) aggregate = true;
        }
        if (aggregate && star) throw err("* not allowed in aggregate query");

        // name checks
        if (where != null) check(where, t, null, false, false);
        Set<String> grouped = null;
        if (aggregate) {
            grouped = new HashSet<>();
            if (groupBy != null) {
                for (String g : groupBy) {
                    if (!t.idx.containsKey(g)) throw err("unknown column " + g);
                    grouped.add(g);
                }
            }
        }
        for (Expr e : outExprs) check(e, t, grouped, true, false);
        if (having != null) check(having, t, grouped, true, false);
        int nOut = outExprs.size();
        // resolve order items
        int[] orderOutIdx = new int[order.size()];
        for (int i = 0; i < order.size(); i++) {
            OrderItem o = order.get(i);
            orderOutIdx[i] = -1;
            if (o.kind == O_INDEX) {
                if (o.index < 1 || o.index > nOut) throw err("ORDER BY position out of range");
                orderOutIdx[i] = (int) o.index - 1;
            } else if (o.kind == O_NAME && outNames.contains(o.name)) {
                orderOutIdx[i] = outNames.indexOf(o.name);
            } else {
                Expr e = o.kind == O_NAME ? new Col(o.name) : o.expr;
                check(e, t, grouped, true, false);
            }
        }

        // filter
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : t.rows) {
            if (where == null || cond(eval(where, new RowEnv(t, row)))) rows.add(row);
        }

        // evaluation units: rows or groups
        List<Env> units = new ArrayList<>();
        if (!aggregate) {
            for (Object[] row : rows) units.add(new RowEnv(t, row));
        } else {
            Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
            if (groupBy == null) {
                groups.put(List.of(), rows);
            } else {
                int[] gi = new int[groupBy.size()];
                for (int i = 0; i < gi.length; i++) gi[i] = t.idx.get(groupBy.get(i));
                for (Object[] row : rows) {
                    List<Object> key = new ArrayList<>(gi.length);
                    for (int g : gi) key.add(row[g]);
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                }
            }
            for (List<Object[]> g : groups.values()) {
                GroupEnv ge = new GroupEnv(t, g);
                if (having == null || cond(eval(having, ge))) units.add(ge);
            }
        }

        record Out(Object[] vals, Object[] keys) {}
        List<Out> outs = new ArrayList<>(units.size());
        for (Env env : units) {
            Object[] vals = new Object[nOut];
            for (int i = 0; i < nOut; i++) vals[i] = eval(outExprs.get(i), env);
            Object[] keys = new Object[order.size()];
            for (int i = 0; i < order.size(); i++) {
                if (orderOutIdx[i] >= 0) {
                    keys[i] = vals[orderOutIdx[i]];
                } else {
                    OrderItem o = order.get(i);
                    Expr e = o.kind == O_NAME ? new Col(o.name) : o.expr;
                    keys[i] = eval(e, env);
                }
            }
            outs.add(new Out(vals, keys));
        }
        if (!order.isEmpty()) {
            outs.sort((a, b) -> {
                for (int i = 0; i < order.size(); i++) {
                    int c = compareForSort(a.keys[i], b.keys[i]);
                    if (c != 0) return order.get(i).desc ? -c : c;
                }
                return 0;
            });
        }
        long from = Math.min(offset, outs.size());
        long to = limit < 0 ? outs.size() : Math.min(outs.size(), from + limit);
        List<List<Object>> result = new ArrayList<>();
        for (long i = from; i < to; i++) {
            result.add(Collections.unmodifiableList(new ArrayList<>(Arrays.asList(outs.get((int) i).vals))));
        }
        return new Result(Collections.unmodifiableList(new ArrayList<>(outNames)),
                Collections.unmodifiableList(result), 0);
    }

    private static OrderItem orderItem(Parser ps) {
        Tok x = ps.peek();
        Tok after = ps.peekAt(1);
        boolean terminal = after.type == EOF
                || (after.type == SYM && (after.text.equals(",") || after.text.equals(";")))
                || (after.type == KW && (after.text.equals("ASC") || after.text.equals("DESC")
                        || after.text.equals("LIMIT")));
        int kind;
        Expr e = null;
        long idx = 0;
        String name = null;
        if (x.type == INT && terminal) {
            ps.next();
            kind = O_INDEX;
            idx = x.num;
        } else if (x.type == ID && terminal) {
            ps.next();
            kind = O_NAME;
            name = x.text;
        } else {
            kind = O_EXPR;
            e = ps.expr();
        }
        boolean desc = false;
        if (ps.acceptKw("DESC")) desc = true;
        else ps.acceptKw("ASC");
        return new OrderItem(kind, e, idx, name, desc);
    }

    private static int compareForSort(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Long x && b instanceof Long y) return Long.compare(x, y);
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return Boolean.compare(x, y);
        throw err("cannot compare values of different types");
    }

    // ------------------------------------------------------- static analysis

    private static boolean hasAgg(Expr e) {
        return switch (e) {
            case Agg a -> true;
            case Lit l -> false;
            case Col c -> false;
            case Neg n -> hasAgg(n.e);
            case Not n -> hasAgg(n.e);
            case IsNull n -> hasAgg(n.e);
            case Bin b -> hasAgg(b.l) || hasAgg(b.r);
        };
    }

    /**
     * Validates names. t == null means columns are forbidden; grouped != null means an aggregate query where
     * columns outside aggregate arguments must be grouped.
     */
    private static void check(Expr e, Table t, Set<String> grouped, boolean allowAgg, boolean inAgg) {
        switch (e) {
            case Lit l -> { }
            case Col c -> {
                if (t == null) throw err("column not allowed here: " + c.name);
                if (!t.idx.containsKey(c.name)) throw err("unknown column " + c.name);
                if (!inAgg && grouped != null && !grouped.contains(c.name)) {
                    throw err("column " + c.name + " must be grouped");
                }
            }
            case Neg n -> check(n.e, t, grouped, allowAgg, inAgg);
            case Not n -> check(n.e, t, grouped, allowAgg, inAgg);
            case IsNull n -> check(n.e, t, grouped, allowAgg, inAgg);
            case Bin b -> {
                check(b.l, t, grouped, allowAgg, inAgg);
                check(b.r, t, grouped, allowAgg, inAgg);
            }
            case Agg a -> {
                if (!allowAgg) throw err("aggregate not allowed here");
                if (inAgg) throw err("nested aggregate");
                if (a.arg != null) check(a.arg, t, grouped, allowAgg, true);
            }
        }
    }

    // ------------------------------------------------------------- evaluation

    private interface Env {
        Object col(String name);

        Object agg(Agg a);
    }

    private static final Env NO_ENV = new Env() {
        public Object col(String name) {
            throw err("no columns here: " + name);
        }

        public Object agg(Agg a) {
            throw err("aggregate not allowed here");
        }
    };

    private static final class RowEnv implements Env {
        final Table t;
        final Object[] row;

        RowEnv(Table t, Object[] row) {
            this.t = t;
            this.row = row;
        }

        public Object col(String name) {
            Integer i = t.idx.get(name);
            if (i == null) throw err("unknown column " + name);
            return row[i];
        }

        public Object agg(Agg a) {
            throw err("aggregate not allowed here");
        }
    }

    private static final class GroupEnv implements Env {
        final Table t;
        final List<Object[]> rows;

        GroupEnv(Table t, List<Object[]> rows) {
            this.t = t;
            this.rows = rows;
        }

        public Object col(String name) {
            Integer i = t.idx.get(name);
            if (i == null) throw err("unknown column " + name);
            return rows.isEmpty() ? null : rows.get(0)[i];
        }

        public Object agg(Agg a) {
            if (a.arg == null) return (long) rows.size();
            long count = 0;
            long sum = 0;
            Object best = null;
            for (Object[] r : rows) {
                Object v = eval(a.arg, new RowEnv(t, r));
                if (v == null) continue;
                count++;
                switch (a.fn) {
                    case "COUNT" -> { }
                    case "SUM" -> {
                        if (!(v instanceof Long l)) throw err("SUM needs INT");
                        sum += l;
                    }
                    default -> {
                        if (!(v instanceof Long) && !(v instanceof String)) throw err(a.fn + " needs INT or TEXT");
                        if (best == null) {
                            best = v;
                        } else {
                            int c = compareForSort(v, best);
                            if (a.fn.equals("MIN") ? c < 0 : c > 0) best = v;
                        }
                    }
                }
            }
            return switch (a.fn) {
                case "COUNT" -> count;
                case "SUM" -> count == 0 ? null : (Object) sum;
                default -> best;
            };
        }
    }

    private static boolean cond(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw err("condition must be boolean");
    }

    private static Object eval(Expr e, Env env) {
        switch (e) {
            case Lit l -> {
                return l.v;
            }
            case Col c -> {
                return env.col(c.name);
            }
            case Agg a -> {
                return env.agg(a);
            }
            case Neg n -> {
                Object v = eval(n.e, env);
                if (v == null) return null;
                if (!(v instanceof Long x)) throw err("unary minus needs INT");
                return -x;
            }
            case Not n -> {
                Object v = eval(n.e, env);
                if (v == null) return null;
                if (!(v instanceof Boolean b)) throw err("NOT needs boolean");
                return !b;
            }
            case IsNull n -> {
                Object v = eval(n.e, env);
                return (v == null) != n.negated;
            }
            case Bin b -> {
                return evalBin(b, env);
            }
        }
    }

    private static Object evalBin(Bin b, Env env) {
        Object l = eval(b.l, env);
        Object r = eval(b.r, env);
        switch (b.op) {
            case "AND", "OR" -> {
                if (l != null && !(l instanceof Boolean)) throw err(b.op + " needs boolean");
                if (r != null && !(r instanceof Boolean)) throw err(b.op + " needs boolean");
                boolean and = b.op.equals("AND");
                // AND: FALSE dominates; OR: TRUE dominates
                Boolean dom = and ? Boolean.FALSE : Boolean.TRUE;
                if (dom.equals(l) || dom.equals(r)) return dom;
                if (l == null || r == null) return null;
                return and;
            }
            case "+", "-", "*", "/", "%" -> {
                if (l != null && !(l instanceof Long)) throw err("arithmetic needs INT");
                if (r != null && !(r instanceof Long)) throw err("arithmetic needs INT");
                if (l == null || r == null) return null;
                long x = (Long) l;
                long y = (Long) r;
                switch (b.op) {
                    case "+":
                        return x + y;
                    case "-":
                        return x - y;
                    case "*":
                        return x * y;
                    case "/":
                        if (y == 0) throw err("division by zero");
                        return x / y;
                    default:
                        if (y == 0) throw err("division by zero");
                        return x % y;
                }
            }
            case "||" -> {
                if (l != null && !(l instanceof String)) throw err("|| needs TEXT");
                if (r != null && !(r instanceof String)) throw err("|| needs TEXT");
                if (l == null || r == null) return null;
                return (String) l + (String) r;
            }
            default -> {
                if (l == null || r == null) {
                    // still reject obviously mixed types when both are present; here one is NULL
                    return null;
                }
                int c;
                if (l instanceof Long x && r instanceof Long y) c = Long.compare(x, y);
                else if (l instanceof String x && r instanceof String y) c = x.compareTo(y);
                else if (l instanceof Boolean x && r instanceof Boolean y) c = Boolean.compare(x, y);
                else throw err("cannot compare values of different types");
                return switch (b.op) {
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
}
