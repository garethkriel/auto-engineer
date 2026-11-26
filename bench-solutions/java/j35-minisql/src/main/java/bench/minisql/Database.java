package bench.minisql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A tiny in-memory SQL database. */
public final class Database {
    public record Result(List<String> columns, List<List<Object>> rows, int affected) { }

    private static final Set<String> RESERVED = Set.of(
        "create", "table", "drop", "insert", "into", "values", "select", "from", "where", "group", "by",
        "having", "order", "asc", "desc", "limit", "offset", "update", "set", "delete", "and", "or", "not",
        "null", "is", "as", "true", "false", "int", "text", "count", "sum", "min", "max");

    // ---------------------------------------------------------------- tokens

    private enum T { KW, ID, INT, STR, SYM, EOF }

    private record Tok(T t, String s, long n) { }

    private static SqlException err(String m) {
        return new SqlException(m);
    }

    private static boolean alpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else if (alpha(c)) {
                int j = i;
                while (j < n && (alpha(s.charAt(j)) || digit(s.charAt(j)))) j++;
                String w = s.substring(i, j).toLowerCase();
                out.add(RESERVED.contains(w) ? new Tok(T.KW, w.toUpperCase(), 0) : new Tok(T.ID, w, 0));
                i = j;
            } else if (digit(c)) {
                int j = i;
                while (j < n && digit(s.charAt(j))) j++;
                try {
                    out.add(new Tok(T.INT, null, Long.parseLong(s.substring(i, j))));
                } catch (NumberFormatException e) {
                    throw err("integer literal out of range");
                }
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
                out.add(new Tok(T.STR, sb.toString(), 0));
                i = j;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals("<>") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("||")) {
                    out.add(new Tok(T.SYM, two.equals("!=") ? "<>" : two, 0));
                    i += 2;
                } else if ("(),;*+-/%=<>".indexOf(c) >= 0) {
                    out.add(new Tok(T.SYM, String.valueOf(c), 0));
                    i++;
                } else {
                    throw err("unexpected character '" + c + "'");
                }
            }
        }
        out.add(new Tok(T.EOF, null, 0));
        return out;
    }

    // ------------------------------------------------------------------- AST

    private sealed interface Expr permits Lit, Col, Neg, Not, Bin, IsNull, Agg { }

    private record Lit(Object v) implements Expr { }

    private record Col(String name) implements Expr { }

    private record Neg(Expr e) implements Expr { }

    private record Not(Expr e) implements Expr { }

    private record Bin(String op, Expr l, Expr r) implements Expr { }

    private record IsNull(Expr e, boolean not) implements Expr { }

    private record Agg(String fn, Expr arg) implements Expr { }

    private record Item(Expr e, String alias) { }

    private record OrderItem(Expr e, boolean desc, boolean single) { }

    private sealed interface Stmt permits Create, Drop, Insert, Update, Delete, Select { }

    private record Create(String table, List<String> cols, List<Boolean> isInt) implements Stmt { }

    private record Drop(String table) implements Stmt { }

    private record Insert(String table, List<String> cols, List<List<Expr>> tuples) implements Stmt { }

    private record Update(String table, List<String> cols, List<Expr> exprs, Expr where) implements Stmt { }

    private record Delete(String table, Expr where) implements Stmt { }

    private record Select(boolean star, List<Item> items, String table, Expr where, List<String> groupBy,
                          Expr having, List<OrderItem> order, Long limit, Long offset) implements Stmt { }

    // ---------------------------------------------------------------- parser

    private static final class Parser {
        private final List<Tok> toks;
        private int p;

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        Tok peek() {
            return toks.get(p);
        }

        Tok next() {
            Tok t = toks.get(p);
            if (t.t != T.EOF) p++;
            return t;
        }

        boolean isKw(String k) {
            Tok t = peek();
            return t.t == T.KW && t.s.equals(k);
        }

        boolean acceptKw(String k) {
            if (isKw(k)) {
                p++;
                return true;
            }
            return false;
        }

        void expectKw(String k) {
            if (!acceptKw(k)) throw err("expected " + k);
        }

        boolean isSym(String s) {
            Tok t = peek();
            return t.t == T.SYM && t.s.equals(s);
        }

        boolean acceptSym(String s) {
            if (isSym(s)) {
                p++;
                return true;
            }
            return false;
        }

        void expectSym(String s) {
            if (!acceptSym(s)) throw err("expected " + s);
        }

        String ident() {
            Tok t = peek();
            if (t.t != T.ID) throw err("expected identifier");
            p++;
            return t.s;
        }

        long intLit() {
            Tok t = peek();
            if (t.t != T.INT) throw err("expected integer");
            p++;
            return t.n;
        }

        Stmt statement() {
            Stmt s;
            Tok t = peek();
            if (t.t != T.KW) throw err("expected statement");
            switch (t.s) {
                case "CREATE" -> s = create();
                case "DROP" -> s = drop();
                case "INSERT" -> s = insert();
                case "UPDATE" -> s = update();
                case "DELETE" -> s = delete();
                case "SELECT" -> s = select();
                default -> throw err("unexpected " + t.s);
            }
            acceptSym(";");
            if (peek().t != T.EOF) throw err("unexpected trailing input");
            return s;
        }

        Stmt create() {
            expectKw("CREATE");
            expectKw("TABLE");
            String name = ident();
            expectSym("(");
            List<String> cols = new ArrayList<>();
            List<Boolean> types = new ArrayList<>();
            do {
                String c = ident();
                if (cols.contains(c)) throw err("duplicate column " + c);
                cols.add(c);
                if (acceptKw("INT")) types.add(true);
                else if (acceptKw("TEXT")) types.add(false);
                else throw err("expected type");
            } while (acceptSym(","));
            expectSym(")");
            return new Create(name, cols, types);
        }

        Stmt drop() {
            expectKw("DROP");
            expectKw("TABLE");
            return new Drop(ident());
        }

        Stmt insert() {
            expectKw("INSERT");
            expectKw("INTO");
            String name = ident();
            List<String> cols = null;
            if (acceptSym("(")) {
                cols = new ArrayList<>();
                do {
                    cols.add(ident());
                } while (acceptSym(","));
                expectSym(")");
            }
            expectKw("VALUES");
            List<List<Expr>> tuples = new ArrayList<>();
            do {
                expectSym("(");
                List<Expr> row = new ArrayList<>();
                do {
                    row.add(expr());
                } while (acceptSym(","));
                expectSym(")");
                tuples.add(row);
            } while (acceptSym(","));
            return new Insert(name, cols, tuples);
        }

        Stmt update() {
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
            return new Update(name, cols, exprs, where);
        }

        Stmt delete() {
            expectKw("DELETE");
            expectKw("FROM");
            String name = ident();
            Expr where = acceptKw("WHERE") ? expr() : null;
            return new Delete(name, where);
        }

        Stmt select() {
            expectKw("SELECT");
            boolean star = false;
            List<Item> items = new ArrayList<>();
            if (acceptSym("*")) {
                star = true;
            } else {
                do {
                    Expr e = expr();
                    String alias = acceptKw("AS") ? ident() : null;
                    items.add(new Item(e, alias));
                } while (acceptSym(","));
            }
            expectKw("FROM");
            String table = ident();
            Expr where = acceptKw("WHERE") ? expr() : null;
            List<String> group = new ArrayList<>();
            if (acceptKw("GROUP")) {
                expectKw("BY");
                do {
                    group.add(ident());
                } while (acceptSym(","));
            }
            Expr having = acceptKw("HAVING") ? expr() : null;
            List<OrderItem> order = new ArrayList<>();
            if (acceptKw("ORDER")) {
                expectKw("BY");
                do {
                    int start = p;
                    Expr e = expr();
                    boolean single = p - start == 1;
                    boolean desc = false;
                    if (acceptKw("DESC")) desc = true;
                    else acceptKw("ASC");
                    order.add(new OrderItem(e, desc, single));
                } while (acceptSym(","));
            }
            Long limit = null;
            Long offset = null;
            if (acceptKw("LIMIT")) {
                limit = intLit();
                if (acceptKw("OFFSET")) offset = intLit();
            }
            return new Select(star, items, table, where, group, having, order, limit, offset);
        }

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
            Tok t = peek();
            if (t.t == T.SYM && (t.s.equals("=") || t.s.equals("<>") || t.s.equals("<") || t.s.equals("<=")
                    || t.s.equals(">") || t.s.equals(">="))) {
                p++;
                return new Bin(t.s, l, add());
            }
            if (acceptKw("IS")) {
                boolean neg = acceptKw("NOT");
                expectKw("NULL");
                return new IsNull(l, neg);
            }
            return l;
        }

        Expr add() {
            Expr l = mul();
            while (true) {
                Tok t = peek();
                if (t.t == T.SYM && (t.s.equals("+") || t.s.equals("-") || t.s.equals("||"))) {
                    p++;
                    l = new Bin(t.s, l, mul());
                } else {
                    return l;
                }
            }
        }

        Expr mul() {
            Expr l = unary();
            while (true) {
                Tok t = peek();
                if (t.t == T.SYM && (t.s.equals("*") || t.s.equals("/") || t.s.equals("%"))) {
                    p++;
                    l = new Bin(t.s, l, unary());
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
            Tok t = next();
            switch (t.t) {
                case INT:
                    return new Lit(t.n);
                case STR:
                    return new Lit(t.s);
                case ID:
                    return new Col(t.s);
                case SYM:
                    if (t.s.equals("(")) {
                        Expr e = expr();
                        expectSym(")");
                        return e;
                    }
                    throw err("unexpected " + t.s);
                case KW:
                    switch (t.s) {
                        case "NULL":
                            return new Lit(null);
                        case "TRUE":
                            return new Lit(Boolean.TRUE);
                        case "FALSE":
                            return new Lit(Boolean.FALSE);
                        case "COUNT", "SUM", "MIN", "MAX": {
                            expectSym("(");
                            Expr arg;
                            if (t.s.equals("COUNT") && acceptSym("*")) arg = null;
                            else arg = expr();
                            expectSym(")");
                            return new Agg(t.s, arg);
                        }
                        default:
                            throw err("unexpected " + t.s);
                    }
                default:
                    throw err("unexpected end of input");
            }
        }
    }

    // ---------------------------------------------------------------- tables

    private static final class Table {
        final List<String> cols;
        final List<Boolean> isInt;
        final Map<String, Integer> idx = new HashMap<>();
        final ArrayList<Object[]> rows = new ArrayList<>();

        Table(List<String> cols, List<Boolean> isInt) {
            this.cols = cols;
            this.isInt = isInt;
            for (int i = 0; i < cols.size(); i++) idx.put(cols.get(i), i);
        }

        int col(String name) {
            Integer i = idx.get(name);
            if (i == null) throw err("unknown column " + name);
            return i;
        }
    }

    private final Map<String, Table> tables = new HashMap<>();

    public Database() { }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw err("unknown table " + name);
        return t;
    }

    public synchronized Result execute(String sql) {
        if (sql == null) throw err("null statement");
        try {
            Stmt s = new Parser(lex(sql)).statement();
            return run(s);
        } catch (SqlException e) {
            throw e;
        } catch (RuntimeException | StackOverflowError e) {
            throw err("error: " + e);
        }
    }

    private static Result affected(int n) {
        return new Result(List.of(), List.of(), n);
    }

    private Result run(Stmt s) {
        if (s instanceof Create c) {
            if (tables.containsKey(c.table)) throw err("table exists: " + c.table);
            tables.put(c.table, new Table(c.cols, c.isInt));
            return affected(0);
        }
        if (s instanceof Drop d) {
            table(d.table);
            tables.remove(d.table);
            return affected(0);
        }
        if (s instanceof Insert i) return insert(i);
        if (s instanceof Update u) return update(u);
        if (s instanceof Delete d) return delete(d);
        return select((Select) s);
    }

    // ------------------------------------------------------------ validation

    private static boolean containsAgg(Expr e) {
        if (e == null) return false;
        return switch (e) {
            case Agg a -> true;
            case Lit l -> false;
            case Col c -> false;
            case Neg n -> containsAgg(n.e);
            case Not n -> containsAgg(n.e);
            case Bin b -> containsAgg(b.l) || containsAgg(b.r);
            case IsNull n -> containsAgg(n.e);
        };
    }

    /** Static name / placement checks. t null means no columns allowed; groupCols null means row mode. */
    private static void check(Expr e, Table t, boolean allowAgg, Set<String> groupCols, boolean inAgg) {
        switch (e) {
            case Lit l -> { }
            case Col c -> {
                if (t == null) throw err("column not allowed here: " + c.name);
                t.col(c.name);
                if (!inAgg && groupCols != null && !groupCols.contains(c.name)) {
                    throw err("column must be grouped: " + c.name);
                }
            }
            case Neg n -> check(n.e, t, allowAgg, groupCols, inAgg);
            case Not n -> check(n.e, t, allowAgg, groupCols, inAgg);
            case Bin b -> {
                check(b.l, t, allowAgg, groupCols, inAgg);
                check(b.r, t, allowAgg, groupCols, inAgg);
            }
            case IsNull n -> check(n.e, t, allowAgg, groupCols, inAgg);
            case Agg a -> {
                if (!allowAgg || inAgg) throw err("aggregate not allowed here");
                if (a.arg != null) check(a.arg, t, allowAgg, groupCols, true);
            }
        }
    }

    // ------------------------------------------------------------ evaluation

    private static Object eval(Expr e, Table t, Object[] row, List<Object[]> group) {
        switch (e) {
            case Lit l -> {
                return l.v;
            }
            case Col c -> {
                int i = t.col(c.name);
                if (row != null) return row[i];
                if (group == null || group.isEmpty()) return null;
                return group.get(0)[i];
            }
            case Neg n -> {
                Object v = eval(n.e, t, row, group);
                if (v == null) return null;
                if (!(v instanceof Long x)) throw err("type error: unary minus needs INT");
                return -x;
            }
            case Not n -> {
                Object v = eval(n.e, t, row, group);
                if (v == null) return null;
                if (!(v instanceof Boolean b)) throw err("type error: NOT needs boolean");
                return !b;
            }
            case IsNull n -> {
                Object v = eval(n.e, t, row, group);
                return (v == null) != n.not;
            }
            case Agg a -> {
                return aggregate(a, t, group);
            }
            case Bin b -> {
                Object l = eval(b.l, t, row, group);
                Object r = eval(b.r, t, row, group);
                return binary(b.op, l, r);
            }
        }
    }

    private static Object binary(String op, Object l, Object r) {
        switch (op) {
            case "AND", "OR": {
                if (l != null && !(l instanceof Boolean)) throw err("type error: " + op + " needs boolean");
                if (r != null && !(r instanceof Boolean)) throw err("type error: " + op + " needs boolean");
                Boolean a = (Boolean) l;
                Boolean b = (Boolean) r;
                if (op.equals("AND")) {
                    if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) return false;
                    if (a == null || b == null) return null;
                    return true;
                }
                if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) return true;
                if (a == null || b == null) return null;
                return false;
            }
            case "=", "<>", "<", "<=", ">", ">=": {
                if (l == null || r == null) return null;
                int c;
                if (l instanceof Long x && r instanceof Long y) c = Long.compare(x, y);
                else if (l instanceof String x && r instanceof String y) c = x.compareTo(y);
                else if (l instanceof Boolean x && r instanceof Boolean y) c = Boolean.compare(x, y);
                else throw err("type error: cannot compare");
                return switch (op) {
                    case "=" -> c == 0;
                    case "<>" -> c != 0;
                    case "<" -> c < 0;
                    case "<=" -> c <= 0;
                    case ">" -> c > 0;
                    default -> c >= 0;
                };
            }
            case "||": {
                if (l != null && !(l instanceof String)) throw err("type error: || needs TEXT");
                if (r != null && !(r instanceof String)) throw err("type error: || needs TEXT");
                if (l == null || r == null) return null;
                return (String) l + (String) r;
            }
            default: {
                if (l != null && !(l instanceof Long)) throw err("type error: arithmetic needs INT");
                if (r != null && !(r instanceof Long)) throw err("type error: arithmetic needs INT");
                if (l == null || r == null) return null;
                long x = (Long) l;
                long y = (Long) r;
                return switch (op) {
                    case "+" -> x + y;
                    case "-" -> x - y;
                    case "*" -> x * y;
                    case "/" -> {
                        if (y == 0) throw err("division by zero");
                        yield x / y;
                    }
                    default -> {
                        if (y == 0) throw err("division by zero");
                        yield x % y;
                    }
                };
            }
        }
    }

    private static Object aggregate(Agg a, Table t, List<Object[]> group) {
        if (a.arg == null) return (long) group.size();
        long count = 0;
        long sum = 0;
        Object best = null;
        for (Object[] row : group) {
            Object v = eval(a.arg, t, row, null);
            if (v == null) continue;
            count++;
            switch (a.fn) {
                case "SUM" -> {
                    if (!(v instanceof Long x)) throw err("type error: SUM needs INT");
                    sum += x;
                }
                case "MIN", "MAX" -> {
                    if (!(v instanceof Long) && !(v instanceof String)) throw err("type error: MIN/MAX need INT or TEXT");
                    if (best == null) best = v;
                    else {
                        int c = compareVals(v, best);
                        if (a.fn.equals("MIN") ? c < 0 : c > 0) best = v;
                    }
                }
                default -> { }
            }
        }
        return switch (a.fn) {
            case "COUNT" -> count;
            case "SUM" -> count == 0 ? null : (Object) sum;
            default -> best;
        };
    }

    private static boolean cond(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw err("type error: condition must be boolean");
    }

    private static int compareVals(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Long x && b instanceof Long y) return Long.compare(x, y);
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return Boolean.compare(x, y);
        throw err("type error: cannot compare values of different types");
    }

    private static Object coerce(Object v, boolean isInt) {
        if (v == null) return null;
        if (isInt && v instanceof Long) return v;
        if (!isInt && v instanceof String) return v;
        throw err("type error: wrong value type for column");
    }

    // ------------------------------------------------------------ statements

    private Result insert(Insert s) {
        Table t = table(s.table);
        int n = t.cols.size();
        int[] map = new int[n];
        int given;
        if (s.cols == null) {
            given = n;
            for (int i = 0; i < n; i++) map[i] = i;
        } else {
            given = s.cols.size();
            map = new int[given];
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < given; i++) {
                map[i] = t.col(s.cols.get(i));
                if (!seen.add(map[i])) throw err("duplicate column in list");
            }
        }
        for (List<Expr> tuple : s.tuples) {
            if (tuple.size() != given) throw err("value count mismatch");
            for (Expr e : tuple) check(e, null, false, null, false);
        }
        List<Object[]> added = new ArrayList<>(s.tuples.size());
        for (List<Expr> tuple : s.tuples) {
            Object[] row = new Object[n];
            for (int i = 0; i < given; i++) {
                row[map[i]] = coerce(eval(tuple.get(i), t, null, null), t.isInt.get(map[i]));
            }
            added.add(row);
        }
        t.rows.addAll(added);
        return affected(added.size());
    }

    private Result update(Update s) {
        Table t = table(s.table);
        int k = s.cols.size();
        int[] target = new int[k];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < k; i++) {
            target[i] = t.col(s.cols.get(i));
            if (!seen.add(target[i])) throw err("column assigned twice");
            check(s.exprs.get(i), t, false, null, false);
        }
        if (s.where != null) check(s.where, t, false, null, false);
        List<Integer> pos = new ArrayList<>();
        List<Object[]> repl = new ArrayList<>();
        for (int r = 0; r < t.rows.size(); r++) {
            Object[] row = t.rows.get(r);
            if (s.where != null && !cond(eval(s.where, t, row, null))) continue;
            Object[] nr = row.clone();
            for (int i = 0; i < k; i++) {
                nr[target[i]] = coerce(eval(s.exprs.get(i), t, row, null), t.isInt.get(target[i]));
            }
            pos.add(r);
            repl.add(nr);
        }
        for (int i = 0; i < pos.size(); i++) t.rows.set(pos.get(i), repl.get(i));
        return affected(pos.size());
    }

    private Result delete(Delete s) {
        Table t = table(s.table);
        if (s.where == null) {
            int n = t.rows.size();
            t.rows.clear();
            return affected(n);
        }
        check(s.where, t, false, null, false);
        ArrayList<Object[]> keep = new ArrayList<>();
        for (Object[] row : t.rows) {
            if (!cond(eval(s.where, t, row, null))) keep.add(row);
        }
        int n = t.rows.size() - keep.size();
        t.rows.clear();
        t.rows.addAll(keep);
        return affected(n);
    }

    private record Entry(Object[] out, Object[] keys) { }

    private Result select(Select s) {
        Table t = table(s.table);
        if (s.where != null) {
            if (containsAgg(s.where)) throw err("aggregate not allowed in WHERE");
            check(s.where, t, false, null, false);
        }
        boolean agg = !s.groupBy.isEmpty() || s.having != null;
        for (Item it : s.items) agg |= containsAgg(it.e);
        for (OrderItem o : s.order) agg |= containsAgg(o.e);
        if (agg && s.star) throw err("* not allowed in aggregate query");
        Set<String> groupCols = null;
        int[] groupIdx = new int[s.groupBy.size()];
        if (agg) {
            groupCols = new HashSet<>(s.groupBy);
            for (int i = 0; i < groupIdx.length; i++) groupIdx[i] = t.col(s.groupBy.get(i));
        }
        // output columns and expressions
        List<String> names = new ArrayList<>();
        List<Expr> exprs = new ArrayList<>();
        if (s.star) {
            for (String c : t.cols) {
                names.add(c);
                exprs.add(new Col(c));
            }
        } else {
            for (int i = 0; i < s.items.size(); i++) {
                Item it = s.items.get(i);
                check(it.e, t, agg, groupCols, false);
                exprs.add(it.e);
                if (it.alias != null) names.add(it.alias);
                else if (it.e instanceof Col c) names.add(c.name);
                else names.add("col" + (i + 1));
            }
        }
        if (s.having != null) check(s.having, t, true, groupCols, false);
        int no = s.order.size();
        int[] ordCol = new int[no];
        for (int i = 0; i < no; i++) {
            OrderItem o = s.order.get(i);
            ordCol[i] = -1;
            if (o.single && o.e instanceof Lit l && l.v instanceof Long k) {
                if (k < 1 || k > names.size()) throw err("ORDER BY position out of range");
                ordCol[i] = (int) (k - 1);
            } else if (o.single && o.e instanceof Col c && names.contains(c.name)) {
                ordCol[i] = names.indexOf(c.name);
            } else {
                check(o.e, t, agg, groupCols, false);
            }
        }
        if (s.having != null && !agg) throw err("internal: having");

        List<Entry> entries = new ArrayList<>();
        int w = exprs.size();
        if (!agg) {
            for (Object[] row : t.rows) {
                if (s.where != null && !cond(eval(s.where, t, row, null))) continue;
                entries.add(entry(exprs, w, s.order, ordCol, t, row, null));
            }
        } else {
            List<List<Object[]>> groups = new ArrayList<>();
            if (s.groupBy.isEmpty()) {
                List<Object[]> all = new ArrayList<>();
                for (Object[] row : t.rows) {
                    if (s.where != null && !cond(eval(s.where, t, row, null))) continue;
                    all.add(row);
                }
                groups.add(all);
            } else {
                Map<List<Object>, List<Object[]>> map = new LinkedHashMap<>();
                for (Object[] row : t.rows) {
                    if (s.where != null && !cond(eval(s.where, t, row, null))) continue;
                    List<Object> key = new ArrayList<>(groupIdx.length);
                    for (int gi : groupIdx) key.add(row[gi]);
                    map.computeIfAbsent(key, x -> new ArrayList<>()).add(row);
                }
                groups.addAll(map.values());
            }
            for (List<Object[]> g : groups) {
                if (s.having != null && !cond(eval(s.having, t, null, g))) continue;
                entries.add(entry(exprs, w, s.order, ordCol, t, null, g));
            }
        }
        if (no > 0) {
            boolean[] desc = new boolean[no];
            for (int i = 0; i < no; i++) desc[i] = s.order.get(i).desc;
            entries.sort((a, b) -> {
                for (int i = 0; i < desc.length; i++) {
                    int c = compareVals(a.keys[i], b.keys[i]);
                    if (c != 0) return desc[i] ? -c : c;
                }
                return 0;
            });
        }
        long off = s.offset == null ? 0 : s.offset;
        long lim = s.limit == null ? Long.MAX_VALUE : s.limit;
        List<List<Object>> rows = new ArrayList<>();
        for (long i = off; i < entries.size() && rows.size() < lim; i++) {
            List<Object> r = new ArrayList<>(w);
            Collections.addAll(r, entries.get((int) i).out);
            rows.add(Collections.unmodifiableList(r));
        }
        return new Result(Collections.unmodifiableList(names), Collections.unmodifiableList(rows), 0);
    }

    private static Entry entry(List<Expr> exprs, int w, List<OrderItem> order, int[] ordCol, Table t,
                               Object[] row, List<Object[]> group) {
        Object[] out = new Object[w];
        for (int i = 0; i < w; i++) out[i] = eval(exprs.get(i), t, row, group);
        Object[] keys = new Object[order.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = ordCol[i] >= 0 ? out[ordCol[i]] : eval(order.get(i).e, t, row, group);
        }
        return new Entry(out, keys);
    }
}
