package ae.minisql;

import ae.minisql.Ast.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Recursive-descent parser. Errors are {@link SqlException}s that start with "line L, col C:". */
final class Parser {
    private static final Set<String> RESERVED = Set.of("SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "LIMIT",
            "OFFSET", "JOIN", "LEFT", "INNER", "ON", "AS", "AND", "OR", "NOT", "IS", "NULL", "IN", "LIKE", "TRUE", "FALSE",
            "DISTINCT", "ASC", "DESC", "CREATE", "TABLE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "DROP",
            "BEGIN", "COMMIT", "ROLLBACK", "PRIMARY", "KEY");
    private static final Set<String> AGGS = Set.of("COUNT", "SUM", "MIN", "MAX");

    private final List<Token> toks;
    private int p;

    private Parser(String sql) {
        this.toks = Lexer.lex(sql);
    }

    /** Parses exactly one statement, optionally followed by one ';'. */
    static Stmt parseOne(String sql) {
        Parser ps = new Parser(sql);
        Stmt s = ps.statement();
        ps.accept(";");
        if (ps.peek().kind() != Token.Kind.EOF) throw ps.err("expected end of statement");
        return s;
    }

    /** Parses a ';'-separated script (empty statements are ignored). */
    static List<Stmt> parseScript(String sql) {
        Parser ps = new Parser(sql);
        List<Stmt> out = new ArrayList<>();
        while (true) {
            while (ps.accept(";")) { /* skip empty statements */ }
            if (ps.peek().kind() == Token.Kind.EOF) return out;
            out.add(ps.statement());
            if (ps.peek().kind() != Token.Kind.EOF && !ps.at(";")) throw ps.err("expected end of statement");
        }
    }

    // ---- token helpers -------------------------------------------------------------------------------------------

    private Token peek() { return toks.get(p); }

    private Token next() { return toks.get(p++); }

    private SqlException err(String expected) {
        Token t = peek();
        String found = t.kind() == Token.Kind.EOF ? "end of input" : "'" + t.text() + "'";
        return new SqlException(t.where() + ": " + expected + ", found " + found);
    }

    private boolean at(String sym) {
        Token t = peek();
        return t.kind() == Token.Kind.SYMBOL && t.text().equals(sym);
    }

    private boolean accept(String sym) {
        if (at(sym)) { p++; return true; }
        return false;
    }

    private void expect(String sym) {
        if (!accept(sym)) throw err("expected '" + sym + "'");
    }

    private boolean atKw(String kw) {
        Token t = peek();
        return t.kind() == Token.Kind.IDENT && t.upper().equals(kw);
    }

    private boolean acceptKw(String kw) {
        if (atKw(kw)) { p++; return true; }
        return false;
    }

    private void expectKw(String kw) {
        if (!acceptKw(kw)) throw err("expected " + kw);
    }

    private String ident(String what) {
        Token t = peek();
        if (t.kind() != Token.Kind.IDENT || RESERVED.contains(t.upper())) throw err("expected " + what);
        p++;
        return t.text().toLowerCase(java.util.Locale.ROOT);
    }

    // ---- statements ----------------------------------------------------------------------------------------------

    private Stmt statement() {
        if (atKw("SELECT")) return select();
        if (acceptKw("CREATE")) return create();
        if (acceptKw("DROP")) { expectKw("TABLE"); return new DropTable(ident("table name")); }
        if (acceptKw("INSERT")) return insert();
        if (acceptKw("UPDATE")) return update();
        if (acceptKw("DELETE")) { expectKw("FROM"); String t = ident("table name"); return new Delete(t, optWhere()); }
        if (acceptKw("BEGIN")) return new Begin();
        if (acceptKw("COMMIT")) return new Commit();
        if (acceptKw("ROLLBACK")) return new Rollback();
        throw err("expected statement");
    }

    private Stmt create() {
        expectKw("TABLE");
        String name = ident("table name");
        expect("(");
        List<ColDef> cols = new ArrayList<>();
        boolean havePk = false;
        do {
            String cn = ident("column name");
            Token tt = peek();
            Type type;
            switch (tt.kind() == Token.Kind.IDENT ? tt.upper() : "") {
                case "INT", "INTEGER" -> type = Type.INT;
                case "TEXT" -> type = Type.TEXT;
                case "BOOL", "BOOLEAN" -> type = Type.BOOL;
                default -> throw new SqlException(tt.where() + ": unknown type, expected INT, TEXT or BOOL");
            }
            p++;
            boolean notNull = false, pk = false;
            while (true) {
                if (acceptKw("NOT")) { expectKw("NULL"); notNull = true; }
                else if (atKw("PRIMARY")) {
                    Token pt = next();
                    expectKw("KEY");
                    if (havePk) throw new SqlException(pt.where() + ": multiple PRIMARY KEY columns");
                    havePk = true;
                    pk = true;
                    notNull = true;
                } else break;
            }
            cols.add(new ColDef(cn, type, notNull, pk));
        } while (accept(","));
        expect(")");
        return new CreateTable(name, cols);
    }

    private Stmt insert() {
        expectKw("INTO");
        String table = ident("table name");
        List<String> cols = null;
        if (accept("(")) {
            cols = new ArrayList<>();
            do { cols.add(ident("column name")); } while (accept(","));
            expect(")");
        }
        expectKw("VALUES");
        List<List<Expr>> rows = new ArrayList<>();
        do {
            expect("(");
            List<Expr> row = new ArrayList<>();
            do { row.add(expr()); } while (accept(","));
            expect(")");
            rows.add(row);
        } while (accept(","));
        return new Insert(table, cols, rows);
    }

    private Stmt update() {
        String table = ident("table name");
        expectKw("SET");
        List<Assign> sets = new ArrayList<>();
        do {
            String c = ident("column name");
            expect("=");
            sets.add(new Assign(c, expr()));
        } while (accept(","));
        return new Update(table, sets, optWhere());
    }

    private Expr optWhere() {
        return acceptKw("WHERE") ? expr() : null;
    }

    private Select select() {
        expectKw("SELECT");
        boolean distinct = acceptKw("DISTINCT");
        List<Item> items = new ArrayList<>();
        do { items.add(item()); } while (accept(","));
        expectKw("FROM");
        From from = from();
        List<Join> joins = new ArrayList<>();
        while (true) {
            boolean left;
            if (acceptKw("LEFT")) { left = true; }
            else if (acceptKw("INNER")) { left = false; }
            else if (atKw("JOIN")) { left = false; }
            else break;
            expectKw("JOIN");
            From f = from();
            expectKw("ON");
            joins.add(new Join(left, f, expr()));
        }
        Expr where = optWhere();
        List<Expr> groupBy = List.of();
        if (acceptKw("GROUP")) {
            expectKw("BY");
            groupBy = new ArrayList<>();
            do { groupBy.add(expr()); } while (accept(","));
        }
        Expr having = acceptKw("HAVING") ? expr() : null;
        List<OrderKey> order = List.of();
        if (acceptKw("ORDER")) {
            expectKw("BY");
            order = new ArrayList<>();
            do {
                Expr e = expr();
                boolean desc = false;
                if (acceptKw("DESC")) desc = true; else acceptKw("ASC");
                order.add(new OrderKey(e, desc));
            } while (accept(","));
        }
        Long limit = null, offset = null;
        if (acceptKw("LIMIT")) {
            limit = number();
            if (acceptKw("OFFSET")) offset = number();
        }
        return new Select(distinct, items, from, joins, where, groupBy, having, order, limit, offset);
    }

    private long number() {
        Token t = peek();
        if (t.kind() != Token.Kind.NUMBER) throw err("expected number");
        p++;
        try {
            return Long.parseLong(t.text());
        } catch (NumberFormatException e) {
            throw new SqlException(t.where() + ": integer literal out of range");
        }
    }

    private From from() {
        String table = ident("table name");
        String alias = table;
        if (acceptKw("AS")) alias = ident("alias");
        else if (peek().kind() == Token.Kind.IDENT && !RESERVED.contains(peek().upper())) alias = ident("alias");
        return new From(table, alias);
    }

    private Item item() {
        if (accept("*")) return new Item(null, null, true, null);
        if (peek().kind() == Token.Kind.IDENT && !RESERVED.contains(peek().upper())
                && toks.get(p + 1).text().equals(".") && toks.get(p + 2).text().equals("*")
                && toks.get(p + 1).kind() == Token.Kind.SYMBOL) {
            String t = ident("table name");
            p += 2;
            return new Item(null, null, true, t);
        }
        Expr e = expr();
        String alias = acceptKw("AS") ? ident("alias") : null;
        return new Item(e, alias, false, null);
    }

    // ---- expressions: OR < AND < NOT < comparison < additive < multiplicative < unary ------------------------------

    Expr expr() {
        Expr l = and();
        while (acceptKw("OR")) l = new Bin("OR", l, and());
        return l;
    }

    private Expr and() {
        Expr l = not();
        while (acceptKw("AND")) l = new Bin("AND", l, not());
        return l;
    }

    private Expr not() {
        if (acceptKw("NOT")) return new Un("NOT", not());
        return comparison();
    }

    private Expr comparison() {
        Expr l = additive();
        for (String op : new String[] {"=", "<>", "<=", ">=", "<", ">"}) {
            if (accept(op)) return new Bin(op, l, additive());
        }
        if (acceptKw("IS")) {
            boolean neg = acceptKw("NOT");
            expectKw("NULL");
            return new IsNull(l, neg);
        }
        boolean neg = false;
        int save = p;
        if (acceptKw("NOT")) neg = true;
        if (acceptKw("IN")) {
            expect("(");
            List<Expr> items = new ArrayList<>();
            do { items.add(expr()); } while (accept(","));
            expect(")");
            return new In(l, items, neg);
        }
        if (acceptKw("LIKE")) return new Like(l, additive(), neg);
        p = save; // a NOT that is not part of NOT IN / NOT LIKE belongs to the caller
        return l;
    }

    private Expr additive() {
        Expr l = multiplicative();
        while (true) {
            if (accept("+")) l = new Bin("+", l, multiplicative());
            else if (accept("-")) l = new Bin("-", l, multiplicative());
            else if (accept("||")) l = new Bin("||", l, multiplicative());
            else return l;
        }
    }

    private Expr multiplicative() {
        Expr l = unary();
        while (true) {
            if (accept("*")) l = new Bin("*", l, unary());
            else if (accept("/")) l = new Bin("/", l, unary());
            else if (accept("%")) l = new Bin("%", l, unary());
            else return l;
        }
    }

    private Expr unary() {
        if (at("-")) {
            p++;
            Token t = peek();
            if (t.kind() == Token.Kind.NUMBER) { // so that -9223372036854775808 is representable
                p++;
                try {
                    return new Lit(Long.parseLong("-" + t.text()));
                } catch (NumberFormatException e) {
                    throw new SqlException(t.where() + ": integer literal out of range");
                }
            }
            return new Un("-", unary());
        }
        return primary();
    }

    private Expr primary() {
        Token t = peek();
        switch (t.kind()) {
            case NUMBER -> {
                p++;
                try {
                    return new Lit(Long.parseLong(t.text()));
                } catch (NumberFormatException e) {
                    throw new SqlException(t.where() + ": integer literal out of range");
                }
            }
            case STRING -> { p++; return new Lit(t.text()); }
            case SYMBOL -> {
                if (accept("(")) {
                    Expr e = expr();
                    expect(")");
                    return e;
                }
                throw err("expected expression");
            }
            case IDENT -> {
                String u = t.upper();
                if (u.equals("NULL")) { p++; return new Lit(null); }
                if (u.equals("TRUE")) { p++; return new Lit(Boolean.TRUE); }
                if (u.equals("FALSE")) { p++; return new Lit(Boolean.FALSE); }
                if (AGGS.contains(u) && toks.get(p + 1).text().equals("(") && toks.get(p + 1).kind() == Token.Kind.SYMBOL) {
                    p += 2;
                    String fn = u.toLowerCase(java.util.Locale.ROOT);
                    if (accept("*")) {
                        if (!fn.equals("count")) throw new SqlException(t.where() + ": only COUNT accepts *");
                        expect(")");
                        return new Agg("count", null, false);
                    }
                    boolean d = acceptKw("DISTINCT");
                    Expr arg = expr();
                    expect(")");
                    return new Agg(fn, arg, d);
                }
                String first = ident("expression");
                if (accept(".")) return new Col(first, ident("column name"));
                return new Col(null, first);
            }
            default -> throw err("expected expression");
        }
    }
}
