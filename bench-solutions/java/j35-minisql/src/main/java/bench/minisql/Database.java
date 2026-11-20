package bench.minisql;

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

    /** Result of a statement. */
    public record Result(List<String> columns, List<List<Object>> rows, int affected) { }

    private static final Set<String> RESERVED = Set.of(
            "CREATE", "TABLE", "DROP", "INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE",
            "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "UPDATE", "SET",
            "DELETE", "AND", "OR", "NOT", "NULL", "IS", "AS", "TRUE", "FALSE", "INT", "TEXT",
            "COUNT", "SUM", "MIN", "MAX");

    private final Map<String, Table> tables = new HashMap<>();

    /** Creates an empty database. */
    public Database() {
    }

    /**
     * Executes exactly one statement.
     *
     * @param sql the statement, optionally followed by a semicolon
     * @return the result
     */
    public Result execute(String sql) {
        if (sql == null) {
            throw new SqlException("null statement");
        }
        try {
            Parser p = new Parser(lex(sql));
            Stmt stmt = p.parseStatement();
            return run(stmt);
        } catch (SqlException e) {
            throw e;
        } catch (StackOverflowError e) {
            throw new SqlException("statement too deeply nested");
        } catch (RuntimeException e) {
            throw new SqlException("internal error: " + e);
        }
    }

    // ------------------------------------------------------------------ tables

    private static final class Table {
        final String name;
        final List<String> cols;
        final boolean[] isInt;
        final Map<String, Integer> index = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();

        Table(String name, List<String> cols, boolean[] isInt) {
            this.name = name;
            this.cols = cols;
            this.isInt = isInt;
            for (int i = 0; i < cols.size(); i++) {
                index.put(cols.get(i), i);
            }
        }
    }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) {
            throw new SqlException("no such table: " + name);
        }
        return t;
    }

    // ------------------------------------------------------------------ lexer

    private enum Kind { IDENT, KW, INT, STR, SYM, EOF }

    private record Tok(Kind kind, String text, long num) { }

    private static boolean letter(char c) {
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
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                i++;
            } else if (letter(c)) {
                int j = i + 1;
                while (j < n && (letter(s.charAt(j)) || digit(s.charAt(j)))) {
                    j++;
                }
                String w = s.substring(i, j);
                String u = w.toUpperCase(Locale.ROOT);
                if (RESERVED.contains(u)) {
                    out.add(new Tok(Kind.KW, u, 0));
                } else {
                    out.add(new Tok(Kind.IDENT, w.toLowerCase(Locale.ROOT), 0));
                }
                i = j;
            } else if (digit(c)) {
                int j = i + 1;
                while (j < n && digit(s.charAt(j))) {
                    j++;
                }
                try {
                    out.add(new Tok(Kind.INT, s.substring(i, j), Long.parseLong(s.substring(i, j))));
                } catch (NumberFormatException e) {
                    throw new SqlException("integer literal out of range");
                }
                i = j;
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char ch = s.charAt(j);
                    if (ch == '\'') {
                        if (j + 1 < n && s.charAt(j + 1) == '\'') {
                            sb.append('\'');
                            j += 2;
                        } else {
                            j++;
                            closed = true;
                            break;
                        }
                    } else {
                        sb.append(ch);
                        j++;
                    }
                }
                if (!closed) {
                    throw new SqlException("unterminated string literal");
                }
                out.add(new Tok(Kind.STR, sb.toString(), 0));
                i = j;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals("<>") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                        || two.equals("||")) {
                    out.add(new Tok(Kind.SYM, two.equals("!=") ? "<>" : two, 0));
                    i += 2;
                } else if ("(),;*+-/%=<>".indexOf(c) >= 0) {
                    out.add(new Tok(Kind.SYM, String.valueOf(c), 0));
                    i++;
                } else {
                    throw new SqlException("unexpected character: " + c);
                }
            }
        }
        out.add(new Tok(Kind.EOF, "", 0));
        return out;
    }

    // ------------------------------------------------------------------ AST

    private sealed interface Expr permits Lit, Col, Neg, Not, Bin, IsNull, Agg { }

    private record Lit(Object v) implements Expr { }

    private static final class Col implements Expr {
        final String name;
        int idx = -1;

        Col(String name) {
            this.name = name;
        }
    }

    private record Neg(Expr e) implements Expr { }

    private record Not(Expr e) implements Expr { }

    private record Bin(String op, Expr l, Expr r) implements Expr { }

    private record IsNull(Expr e, boolean negated) implements Expr { }

    private record Agg(String fn, Expr arg) implements Expr { }

    private sealed interface Stmt permits Create, Drop, Insert, Update, Delete, Select { }

    private record Create(String name, List<String> cols, List<Boolean> ints) implements Stmt { }

    private record Drop(String name) implements Stmt { }

    private record Insert(String table, List<String> cols, List<List<Expr>> tuples) implements Stmt { }

    private record Update(String table, List<String> cols, List<Expr> exprs, Expr where) implements Stmt { }

    private record Delete(String table, Expr where) implements Stmt { }

    private record Item(Expr expr, String alias) { }

    private record Order(Expr expr, Long bareInt, String bareName, boolean desc) { }

    private record Select(boolean star, List<Item> items, String table, Expr where, List<String> groupBy,
                          Expr having, List<Order> order, long limit, long offset) implements Stmt { }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        private final List<Tok> toks;
        private int pos;

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        private Tok peek() {
            return toks.get(pos);
        }

        private Tok next() {
            Tok t = toks.get(pos);
            if (t.kind() != Kind.EOF) {
                pos++;
            }
            return t;
        }

        private boolean isKw(String k) {
            Tok t = peek();
            return t.kind() == Kind.KW && t.text().equals(k);
        }

        private boolean isSym(String s) {
            Tok t = peek();
            return t.kind() == Kind.SYM && t.text().equals(s);
        }

        private boolean acceptKw(String k) {
            if (isKw(k)) {
                pos++;
                return true;
            }
            return false;
        }

        private boolean acceptSym(String s) {
            if (isSym(s)) {
                pos++;
                return true;
            }
            return false;
        }

        private void expectKw(String k) {
            if (!acceptKw(k)) {
                throw new SqlException("expected " + k + " but found '" + peek().text() + "'");
            }
        }

        private void expectSym(String s) {
            if (!acceptSym(s)) {
                throw new SqlException("expected '" + s + "' but found '" + peek().text() + "'");
            }
        }

        private String ident() {
            Tok t = peek();
            if (t.kind() != Kind.IDENT) {
                throw new SqlException("expected identifier but found '" + t.text() + "'");
            }
            pos++;
            return t.text();
        }

        Stmt parseStatement() {
            Stmt s;
            Tok t = peek();
            if (t.kind() != Kind.KW) {
                throw new SqlException("syntax error at '" + t.text() + "'");
            }
            switch (t.text()) {
                case "CREATE" -> s = parseCreate();
                case "DROP" -> {
                    next();
                    expectKw("TABLE");
                    s = new Drop(ident());
                }
                case "INSERT" -> s = parseInsert();
                case "UPDATE" -> s = parseUpdate();
                case "DELETE" -> s = parseDelete();
                case "SELECT" -> s = parseSelect();
                default -> throw new SqlException("syntax error at '" + t.text() + "'");
            }
            acceptSym(";");
            if (peek().kind() != Kind.EOF) {
                throw new SqlException("unexpected trailing input '" + peek().text() + "'");
            }
            return s;
        }

        private Stmt parseCreate() {
            next();
            expectKw("TABLE");
            String name = ident();
            expectSym("(");
            List<String> cols = new ArrayList<>();
            List<Boolean> ints = new ArrayList<>();
            do {
                cols.add(ident());
                if (acceptKw("INT")) {
                    ints.add(true);
                } else if (acceptKw("TEXT")) {
                    ints.add(false);
                } else {
                    throw new SqlException("expected column type");
                }
            } while (acceptSym(","));
            expectSym(")");
            return new Create(name, cols, ints);
        }

        private Stmt parseInsert() {
            next();
            expectKw("INTO");
            String table = ident();
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
                List<Expr> tuple = new ArrayList<>();
                do {
                    tuple.add(parseExpr());
                } while (acceptSym(","));
                expectSym(")");
                tuples.add(tuple);
            } while (acceptSym(","));
            return new Insert(table, cols, tuples);
        }

        private Stmt parseUpdate() {
            next();
            String table = ident();
            expectKw("SET");
            List<String> cols = new ArrayList<>();
            List<Expr> exprs = new ArrayList<>();
            do {
                cols.add(ident());
                expectSym("=");
                exprs.add(parseExpr());
            } while (acceptSym(","));
            Expr where = acceptKw("WHERE") ? parseExpr() : null;
            return new Update(table, cols, exprs, where);
        }

        private Stmt parseDelete() {
            next();
            expectKw("FROM");
            String table = ident();
            Expr where = acceptKw("WHERE") ? parseExpr() : null;
            return new Delete(table, where);
        }

        private Stmt parseSelect() {
            next();
            boolean star = false;
            List<Item> items = new ArrayList<>();
            if (acceptSym("*")) {
                star = true;
            } else {
                do {
                    Expr e = parseExpr();
                    String alias = acceptKw("AS") ? ident() : null;
                    items.add(new Item(e, alias));
                } while (acceptSym(","));
            }
            expectKw("FROM");
            String table = ident();
            Expr where = acceptKw("WHERE") ? parseExpr() : null;
            List<String> groupBy = new ArrayList<>();
            if (acceptKw("GROUP")) {
                expectKw("BY");
                do {
                    groupBy.add(ident());
                } while (acceptSym(","));
            }
            Expr having = acceptKw("HAVING") ? parseExpr() : null;
            List<Order> order = new ArrayList<>();
            if (acceptKw("ORDER")) {
                expectKw("BY");
                do {
                    int start = pos;
                    Expr e = parseExpr();
                    Long bareInt = null;
                    String bareName = null;
                    if (pos == start + 1) {
                        Tok t = toks.get(start);
                        if (t.kind() == Kind.INT) {
                            bareInt = t.num();
                        } else if (t.kind() == Kind.IDENT) {
                            bareName = t.text();
                        }
                    }
                    boolean desc = false;
                    if (acceptKw("DESC")) {
                        desc = true;
                    } else {
                        acceptKw("ASC");
                    }
                    order.add(new Order(e, bareInt, bareName, desc));
                } while (acceptSym(","));
            }
            long limit = -1;
            long offset = 0;
            if (acceptKw("LIMIT")) {
                limit = intLiteral();
                if (acceptKw("OFFSET")) {
                    offset = intLiteral();
                }
            }
            return new Select(star, items, table, where, groupBy, having, order, limit, offset);
        }

        private long intLiteral() {
            Tok t = peek();
            if (t.kind() != Kind.INT) {
                throw new SqlException("expected integer but found '" + t.text() + "'");
            }
            pos++;
            return t.num();
        }

        Expr parseExpr() {
            Expr l = parseAnd();
            while (acceptKw("OR")) {
                l = new Bin("OR", l, parseAnd());
            }
            return l;
        }

        private Expr parseAnd() {
            Expr l = parseNot();
            while (acceptKw("AND")) {
                l = new Bin("AND", l, parseNot());
            }
            return l;
        }

        private Expr parseNot() {
            if (acceptKw("NOT")) {
                return new Not(parseNot());
            }
            return parseCmp();
        }

        private Expr parseCmp() {
            Expr l = parseAdd();
            Tok t = peek();
            if (t.kind() == Kind.SYM) {
                switch (t.text()) {
                    case "=", "<>", "<", "<=", ">", ">=" -> {
                        pos++;
                        return new Bin(t.text(), l, parseAdd());
                    }
                    default -> { }
                }
            } else if (isKw("IS")) {
                pos++;
                boolean neg = acceptKw("NOT");
                expectKw("NULL");
                return new IsNull(l, neg);
            }
            return l;
        }

        private Expr parseAdd() {
            Expr l = parseMul();
            while (isSym("+") || isSym("-") || isSym("||")) {
                String op = next().text();
                l = new Bin(op, l, parseMul());
            }
            return l;
        }

        private Expr parseMul() {
            Expr l = parseUnary();
            while (isSym("*") || isSym("/") || isSym("%")) {
                String op = next().text();
                l = new Bin(op, l, parseUnary());
            }
            return l;
        }

        private Expr parseUnary() {
            if (acceptSym("-")) {
                return new Neg(parseUnary());
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            Tok t = next();
            switch (t.kind()) {
                case INT:
                    return new Lit(t.num());
                case STR:
                    return new Lit(t.text());
                case IDENT:
                    return new Col(t.text());
                case SYM:
                    if (t.text().equals("(")) {
                        Expr e = parseExpr();
                        expectSym(")");
                        return e;
                    }
                    break;
                case KW:
                    switch (t.text()) {
                        case "NULL":
                            return new Lit(null);
                        case "TRUE":
                            return new Lit(Boolean.TRUE);
                        case "FALSE":
                            return new Lit(Boolean.FALSE);
                        case "COUNT", "SUM", "MIN", "MAX": {
                            expectSym("(");
                            Expr arg;
                            if (t.text().equals("COUNT") && acceptSym("*")) {
                                arg = null;
                            } else {
                                arg = parseExpr();
                            }
                            expectSym(")");
                            return new Agg(t.text(), arg);
                        }
                        default:
                            break;
                    }
                    break;
                default:
                    break;
            }
            throw new SqlException("syntax error at '" + t.text() + "'");
        }
    }

    // ------------------------------------------------------------------ validation

    private static boolean hasAgg(Expr e) {
        return switch (e) {
            case Lit l -> false;
            case Col c -> false;
            case Neg n -> hasAgg(n.e());
            case Not n -> hasAgg(n.e());
            case Bin b -> hasAgg(b.l()) || hasAgg(b.r());
            case IsNull n -> hasAgg(n.e());
            case Agg a -> true;
        };
    }

    /** Resolves columns and checks aggregate placement. {@code grouped == null} means no restriction. */
    private static void validate(Expr e, Table t, boolean allowAgg, Set<Integer> grouped, boolean inAgg) {
        switch (e) {
            case Lit l -> { }
            case Col c -> {
                if (t == null) {
                    throw new SqlException("expression cannot refer to columns: " + c.name);
                }
                Integer i = t.index.get(c.name);
                if (i == null) {
                    throw new SqlException("no such column: " + c.name);
                }
                c.idx = i;
                if (!inAgg && grouped != null && !grouped.contains(i)) {
                    throw new SqlException("column must be grouped or aggregated: " + c.name);
                }
            }
            case Neg n -> validate(n.e(), t, allowAgg, grouped, inAgg);
            case Not n -> validate(n.e(), t, allowAgg, grouped, inAgg);
            case Bin b -> {
                validate(b.l(), t, allowAgg, grouped, inAgg);
                validate(b.r(), t, allowAgg, grouped, inAgg);
            }
            case IsNull n -> validate(n.e(), t, allowAgg, grouped, inAgg);
            case Agg a -> {
                if (!allowAgg) {
                    throw new SqlException("aggregate not allowed here");
                }
                if (inAgg) {
                    throw new SqlException("nested aggregates");
                }
                if (a.arg() != null) {
                    validate(a.arg(), t, true, grouped, true);
                }
            }
        }
    }

    // ------------------------------------------------------------------ evaluation

    private static Object eval(Expr e, Object[] row, List<Object[]> group) {
        switch (e) {
            case Lit l -> {
                return l.v();
            }
            case Col c -> {
                if (row != null) {
                    return row[c.idx];
                }
                if (group == null || group.isEmpty()) {
                    return null;
                }
                return group.get(0)[c.idx];
            }
            case Neg n -> {
                Object v = eval(n.e(), row, group);
                if (v == null) {
                    return null;
                }
                if (!(v instanceof Long x)) {
                    throw new SqlException("unary minus needs INT");
                }
                return -x;
            }
            case Not n -> {
                Object v = eval(n.e(), row, group);
                if (v == null) {
                    return null;
                }
                if (!(v instanceof Boolean b)) {
                    throw new SqlException("NOT needs a boolean");
                }
                return !b;
            }
            case IsNull n -> {
                Object v = eval(n.e(), row, group);
                return (v == null) != n.negated();
            }
            case Agg a -> {
                return aggregate(a, group);
            }
            case Bin b -> {
                Object l = eval(b.l(), row, group);
                Object r = eval(b.r(), row, group);
                return binary(b.op(), l, r);
            }
        }
    }

    private static Object binary(String op, Object l, Object r) {
        switch (op) {
            case "AND", "OR": {
                if ((l != null && !(l instanceof Boolean)) || (r != null && !(r instanceof Boolean))) {
                    throw new SqlException(op + " needs booleans");
                }
                Boolean a = (Boolean) l;
                Boolean b = (Boolean) r;
                if (op.equals("AND")) {
                    if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) {
                        return Boolean.FALSE;
                    }
                    return a == null || b == null ? null : Boolean.TRUE;
                }
                if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) {
                    return Boolean.TRUE;
                }
                return a == null || b == null ? null : Boolean.FALSE;
            }
            case "||": {
                if ((l != null && !(l instanceof String)) || (r != null && !(r instanceof String))) {
                    throw new SqlException("|| needs TEXT");
                }
                return l == null || r == null ? null : (String) l + (String) r;
            }
            case "+", "-", "*", "/", "%": {
                if ((l != null && !(l instanceof Long)) || (r != null && !(r instanceof Long))) {
                    throw new SqlException("arithmetic needs INT");
                }
                if (l == null || r == null) {
                    return null;
                }
                long a = (Long) l;
                long b = (Long) r;
                switch (op) {
                    case "+":
                        return a + b;
                    case "-":
                        return a - b;
                    case "*":
                        return a * b;
                    case "/":
                        if (b == 0) {
                            throw new SqlException("division by zero");
                        }
                        return a / b;
                    default:
                        if (b == 0) {
                            throw new SqlException("division by zero");
                        }
                        return a % b;
                }
            }
            default: {
                if (l == null || r == null) {
                    return null;
                }
                int c = compareSame(l, r);
                return switch (op) {
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

    /** Compares two non-null values of the same type, else throws. */
    private static int compareSame(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        throw new SqlException("cannot compare values of different or unsupported types");
    }

    /** Ordering with NULL first. */
    private static int compareNullsFirst(Object a, Object b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        if (b == null) {
            return 1;
        }
        return compareSame(a, b);
    }

    private static Object aggregate(Agg a, List<Object[]> group) {
        if (group == null) {
            throw new SqlException("aggregate not allowed here");
        }
        if (a.arg() == null) {
            return (long) group.size();
        }
        long count = 0;
        long sum = 0;
        Object best = null;
        for (Object[] r : group) {
            Object v = eval(a.arg(), r, null);
            if (v == null) {
                continue;
            }
            count++;
            switch (a.fn()) {
                case "SUM" -> {
                    if (!(v instanceof Long x)) {
                        throw new SqlException("SUM needs INT");
                    }
                    sum += x;
                }
                case "MIN", "MAX" -> {
                    if (!(v instanceof Long) && !(v instanceof String)) {
                        throw new SqlException(a.fn() + " needs INT or TEXT");
                    }
                    if (best == null) {
                        best = v;
                    } else {
                        int c = compareSame(v, best);
                        if (a.fn().equals("MIN") ? c < 0 : c > 0) {
                            best = v;
                        }
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

    private static boolean truth(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new SqlException("condition must be boolean");
    }

    private static void checkStore(Object v, boolean isInt, String col) {
        if (v == null) {
            return;
        }
        if (isInt ? !(v instanceof Long) : !(v instanceof String)) {
            throw new SqlException("wrong type for column " + col);
        }
    }

    // ------------------------------------------------------------------ execution

    private static final Result EMPTY_OK = new Result(List.of(), List.of(), 0);

    private Result run(Stmt stmt) {
        return switch (stmt) {
            case Create c -> create(c);
            case Drop d -> {
                table(d.name());
                tables.remove(d.name());
                yield EMPTY_OK;
            }
            case Insert i -> insert(i);
            case Update u -> update(u);
            case Delete d -> delete(d);
            case Select s -> select(s);
        };
    }

    private Result create(Create c) {
        if (tables.containsKey(c.name())) {
            throw new SqlException("table already exists: " + c.name());
        }
        if (new HashSet<>(c.cols()).size() != c.cols().size()) {
            throw new SqlException("duplicate column name");
        }
        boolean[] ints = new boolean[c.cols().size()];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = c.ints().get(i);
        }
        tables.put(c.name(), new Table(c.name(), List.copyOf(c.cols()), ints));
        return EMPTY_OK;
    }

    private Result insert(Insert ins) {
        Table t = table(ins.table());
        int[] target;
        if (ins.cols() == null) {
            target = new int[t.cols.size()];
            for (int i = 0; i < target.length; i++) {
                target[i] = i;
            }
        } else {
            target = new int[ins.cols().size()];
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < target.length; i++) {
                Integer idx = t.index.get(ins.cols().get(i));
                if (idx == null) {
                    throw new SqlException("no such column: " + ins.cols().get(i));
                }
                if (!seen.add(idx)) {
                    throw new SqlException("duplicate column in list: " + ins.cols().get(i));
                }
                target[i] = idx;
            }
        }
        for (List<Expr> tuple : ins.tuples()) {
            if (tuple.size() != target.length) {
                throw new SqlException("value count does not match column count");
            }
            for (Expr e : tuple) {
                validate(e, null, false, null, false);
            }
        }
        List<Object[]> added = new ArrayList<>(ins.tuples().size());
        int width = t.cols.size();
        for (List<Expr> tuple : ins.tuples()) {
            Object[] row = new Object[width];
            for (int i = 0; i < target.length; i++) {
                Object v = eval(tuple.get(i), null, null);
                checkStore(v, t.isInt[target[i]], t.cols.get(target[i]));
                row[target[i]] = v;
            }
            added.add(row);
        }
        t.rows.addAll(added);
        return new Result(List.of(), List.of(), added.size());
    }

    private Result update(Update u) {
        Table t = table(u.table());
        int[] target = new int[u.cols().size()];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < target.length; i++) {
            Integer idx = t.index.get(u.cols().get(i));
            if (idx == null) {
                throw new SqlException("no such column: " + u.cols().get(i));
            }
            if (!seen.add(idx)) {
                throw new SqlException("column set twice: " + u.cols().get(i));
            }
            target[i] = idx;
        }
        for (Expr e : u.exprs()) {
            validate(e, t, false, null, false);
        }
        if (u.where() != null) {
            validate(u.where(), t, false, null, false);
        }
        List<Object[]> newRows = new ArrayList<>(t.rows.size());
        int affected = 0;
        for (Object[] row : t.rows) {
            if (u.where() == null || truth(eval(u.where(), row, null))) {
                Object[] nr = row.clone();
                for (int i = 0; i < target.length; i++) {
                    Object v = eval(u.exprs().get(i), row, null);
                    checkStore(v, t.isInt[target[i]], t.cols.get(target[i]));
                    nr[target[i]] = v;
                }
                newRows.add(nr);
                affected++;
            } else {
                newRows.add(row);
            }
        }
        t.rows = newRows;
        return new Result(List.of(), List.of(), affected);
    }

    private Result delete(Delete d) {
        Table t = table(d.table());
        if (d.where() != null) {
            validate(d.where(), t, false, null, false);
        }
        List<Object[]> kept = new ArrayList<>();
        int removed = 0;
        for (Object[] row : t.rows) {
            if (d.where() == null || truth(eval(d.where(), row, null))) {
                removed++;
            } else {
                kept.add(row);
            }
        }
        t.rows = kept;
        return new Result(List.of(), List.of(), removed);
    }

    private record Out(Object[] vals, Object[] keys) { }

    private Result select(Select s) {
        Table t = table(s.table());

        // output columns
        List<String> names = new ArrayList<>();
        List<Expr> exprs = new ArrayList<>();
        if (s.star()) {
            names.addAll(t.cols);
        } else {
            for (int i = 0; i < s.items().size(); i++) {
                Item it = s.items().get(i);
                exprs.add(it.expr());
                if (it.alias() != null) {
                    names.add(it.alias());
                } else if (it.expr() instanceof Col c) {
                    names.add(c.name);
                } else {
                    names.add("col" + (i + 1));
                }
            }
        }

        // classify order items: output column index (>=0) or expression (-1)
        int nOrder = s.order().size();
        int[] outIdx = new int[nOrder];
        boolean orderAgg = false;
        for (int i = 0; i < nOrder; i++) {
            Order o = s.order().get(i);
            outIdx[i] = -1;
            if (o.bareInt() != null) {
                long k = o.bareInt();
                if (k < 1 || k > names.size()) {
                    throw new SqlException("ORDER BY position out of range: " + k);
                }
                outIdx[i] = (int) k - 1;
            } else if (o.bareName() != null && names.contains(o.bareName())) {
                outIdx[i] = names.indexOf(o.bareName());
            } else if (hasAgg(o.expr())) {
                orderAgg = true;
            }
        }
        boolean aggQuery = !s.groupBy().isEmpty() || s.having() != null || orderAgg;
        for (Expr e : exprs) {
            if (hasAgg(e)) {
                aggQuery = true;
            }
        }
        if (aggQuery && s.star()) {
            throw new SqlException("* is not allowed in an aggregate query");
        }

        // validation
        if (s.where() != null) {
            validate(s.where(), t, false, null, false);
        }
        Set<Integer> grouped = null;
        int[] groupIdx = new int[s.groupBy().size()];
        if (aggQuery) {
            grouped = new HashSet<>();
            for (int i = 0; i < groupIdx.length; i++) {
                Integer idx = t.index.get(s.groupBy().get(i));
                if (idx == null) {
                    throw new SqlException("no such column: " + s.groupBy().get(i));
                }
                groupIdx[i] = idx;
                grouped.add(idx);
            }
        }
        for (Expr e : exprs) {
            validate(e, t, true, grouped, false);
        }
        if (s.having() != null) {
            validate(s.having(), t, true, grouped, false);
        }
        for (int i = 0; i < nOrder; i++) {
            if (outIdx[i] < 0) {
                validate(s.order().get(i).expr(), t, true, grouped, false);
            }
        }

        // filter
        List<Object[]> rows;
        if (s.where() == null) {
            rows = t.rows;
        } else {
            rows = new ArrayList<>();
            for (Object[] r : t.rows) {
                if (truth(eval(s.where(), r, null))) {
                    rows.add(r);
                }
            }
        }

        // produce output rows
        List<Out> outs = new ArrayList<>();
        int width = names.size();
        if (!aggQuery) {
            for (Object[] r : rows) {
                Object[] vals;
                if (s.star()) {
                    vals = r.clone();
                } else {
                    vals = new Object[width];
                    for (int i = 0; i < width; i++) {
                        vals[i] = eval(exprs.get(i), r, null);
                    }
                }
                outs.add(new Out(vals, orderKeys(s, outIdx, vals, r, null)));
            }
        } else {
            Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
            if (groupIdx.length == 0) {
                groups.put(List.of(), rows);
            } else {
                for (Object[] r : rows) {
                    Object[] key = new Object[groupIdx.length];
                    for (int i = 0; i < key.length; i++) {
                        key[i] = r[groupIdx[i]];
                    }
                    groups.computeIfAbsent(Arrays.asList(key), k -> new ArrayList<>()).add(r);
                }
            }
            for (List<Object[]> g : groups.values()) {
                if (s.having() != null && !truth(eval(s.having(), null, g))) {
                    continue;
                }
                Object[] vals = new Object[width];
                for (int i = 0; i < width; i++) {
                    vals[i] = eval(exprs.get(i), null, g);
                }
                outs.add(new Out(vals, orderKeys(s, outIdx, vals, null, g)));
            }
        }

        // sort
        if (nOrder > 0) {
            outs.sort((a, b) -> {
                for (int i = 0; i < nOrder; i++) {
                    int c = compareNullsFirst(a.keys()[i], b.keys()[i]);
                    if (c != 0) {
                        return s.order().get(i).desc() ? -c : c;
                    }
                }
                return 0;
            });
        }

        // limit / offset
        int from = (int) Math.min(s.offset(), outs.size());
        long end = s.limit() < 0 ? outs.size() : Math.min((long) outs.size(), from + s.limit());
        List<List<Object>> result = new ArrayList<>();
        for (int i = from; i < end; i++) {
            result.add(Collections.unmodifiableList(new ArrayList<>(Arrays.asList(outs.get(i).vals()))));
        }
        return new Result(List.copyOf(names), Collections.unmodifiableList(result), 0);
    }

    private static Object[] orderKeys(Select s, int[] outIdx, Object[] vals, Object[] row, List<Object[]> group) {
        Object[] keys = new Object[outIdx.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = outIdx[i] >= 0 ? vals[outIdx[i]] : eval(s.order().get(i).expr(), row, group);
        }
        return keys;
    }
}
