package ae.minisql;

import ae.minisql.Ast.*;
import ae.minisql.Lexer.Kind;
import ae.minisql.Lexer.Token;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Recursive-descent parser: one method per precedence level, errors carry the offending token's offset. */
final class Parser {
    private static final Set<String> RESERVED = Set.of("SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER",
            "LIMIT", "OFFSET", "JOIN", "INNER", "LEFT", "ON", "AS", "AND", "OR", "NOT", "NULL", "TRUE", "FALSE", "IS",
            "IN", "LIKE", "BETWEEN", "DISTINCT", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
            "DROP", "TABLE", "BEGIN", "COMMIT", "ROLLBACK", "ASC", "DESC", "PRIMARY", "KEY");

    private final String sql;
    private final List<Token> toks;
    private int i;
    private int prevEnd;

    private Parser(String sql) {
        this.sql = sql;
        this.toks = Lexer.lex(sql);
    }

    /** Parses ';'-separated statements; empty statements are skipped. */
    static List<Stmt> parseScript(String sql) {
        Parser p = new Parser(sql);
        List<Stmt> out = new ArrayList<>();
        while (p.peek().kind() != Kind.EOF) {
            if (p.isSym(";")) {
                p.next();
                continue;
            }
            out.add(p.statement());
            if (p.peek().kind() != Kind.EOF && !p.isSym(";")) {
                throw p.error("expected ';' or end of input");
            }
        }
        return out;
    }

    // ---- token helpers ----

    private Token peek() {
        return toks.get(i);
    }

    private Token peekAt(int k) {
        return toks.get(Math.min(i + k, toks.size() - 1));
    }

    private Token next() {
        Token t = toks.get(i);
        if (t.kind() != Kind.EOF) {
            i++;
        }
        prevEnd = t.end();
        return t;
    }

    private SqlException error(String message) {
        Token t = peek();
        String found = t.kind() == Kind.EOF ? "end of input" : "'" + t.text() + "'";
        return new SqlException(message + ", found " + found, t.pos());
    }

    private static boolean isKw(Token t, String kw) {
        return t.kind() == Kind.IDENT && t.text().equalsIgnoreCase(kw);
    }

    private boolean isKw(String kw) {
        return isKw(peek(), kw);
    }

    private boolean isSym(String s) {
        return peek().kind() == Kind.SYM && peek().text().equals(s);
    }

    private boolean acceptKw(String kw) {
        if (isKw(kw)) {
            next();
            return true;
        }
        return false;
    }

    private boolean acceptSym(String s) {
        if (isSym(s)) {
            next();
            return true;
        }
        return false;
    }

    private void expectKw(String kw) {
        if (!acceptKw(kw)) {
            throw error("expected " + kw);
        }
    }

    private void expectSym(String s) {
        if (!acceptSym(s)) {
            throw error("expected '" + s + "'");
        }
    }

    private static boolean reserved(Token t) {
        return t.kind() == Kind.IDENT && RESERVED.contains(t.text().toUpperCase(Locale.ROOT));
    }

    private String ident(String what) {
        Token t = peek();
        if (t.kind() != Kind.IDENT || reserved(t)) {
            throw error("expected " + what);
        }
        next();
        return t.text().toLowerCase(Locale.ROOT);
    }

    // ---- statements ----

    private Stmt statement() {
        Token t = peek();
        if (t.kind() == Kind.IDENT) {
            switch (t.text().toUpperCase(Locale.ROOT)) {
                case "SELECT": return select();
                case "INSERT": return insert();
                case "UPDATE": return update();
                case "DELETE": return delete();
                case "CREATE": return create();
                case "DROP":
                    next();
                    expectKw("TABLE");
                    return new DropTable(ident("table name"));
                case "BEGIN":
                    next();
                    return new Begin();
                case "COMMIT":
                    next();
                    return new Commit();
                case "ROLLBACK":
                    next();
                    return new Rollback();
                default:
                    break;
            }
        }
        throw error("expected a statement");
    }

    private Stmt create() {
        next();
        expectKw("TABLE");
        String name = ident("table name");
        expectSym("(");
        List<ColDef> cols = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean havePk = false;
        do {
            int pos = peek().pos();
            String col = ident("column name");
            if (!seen.add(col)) {
                throw new SqlException("duplicate column " + col, pos);
            }
            Token typeTok = peek();
            String type = typeTok.kind() == Kind.IDENT ? typeTok.text().toUpperCase(Locale.ROOT) : "";
            if (!type.equals("INT") && !type.equals("TEXT") && !type.equals("BOOL")) {
                throw error("expected column type INT, TEXT or BOOL");
            }
            next();
            boolean notNull = false;
            boolean pk = false;
            while (true) {
                if (isKw("NOT")) {
                    next();
                    expectKw("NULL");
                    notNull = true;
                } else if (isKw("PRIMARY")) {
                    int pkPos = peek().pos();
                    next();
                    expectKw("KEY");
                    if (havePk) {
                        throw new SqlException("only one PRIMARY KEY column is supported", pkPos);
                    }
                    havePk = true;
                    pk = true;
                    notNull = true;
                } else {
                    break;
                }
            }
            cols.add(new ColDef(col, type, notNull, pk));
        } while (acceptSym(","));
        expectSym(")");
        return new CreateTable(name, cols);
    }

    private Stmt insert() {
        next();
        expectKw("INTO");
        String table = ident("table name");
        List<String> cols = null;
        if (acceptSym("(")) {
            cols = new ArrayList<>();
            do {
                cols.add(ident("column name"));
            } while (acceptSym(","));
            expectSym(")");
        }
        expectKw("VALUES");
        List<List<Expr>> rows = new ArrayList<>();
        do {
            expectSym("(");
            List<Expr> row = new ArrayList<>();
            do {
                row.add(expr());
            } while (acceptSym(","));
            expectSym(")");
            rows.add(row);
        } while (acceptSym(","));
        return new Insert(table, cols, rows);
    }

    private Stmt update() {
        next();
        String table = ident("table name");
        expectKw("SET");
        List<Assign> assigns = new ArrayList<>();
        do {
            int pos = peek().pos();
            String col = ident("column name");
            expectSym("=");
            assigns.add(new Assign(col, expr(), pos));
        } while (acceptSym(","));
        Expr where = acceptKw("WHERE") ? expr() : null;
        return new Update(table, assigns, where);
    }

    private Stmt delete() {
        next();
        expectKw("FROM");
        String table = ident("table name");
        Expr where = acceptKw("WHERE") ? expr() : null;
        return new Delete(table, where);
    }

    private TableRef tableRef() {
        int pos = peek().pos();
        String name = ident("table name");
        String alias = name;
        if (acceptKw("AS")) {
            alias = ident("table alias");
        } else if (peek().kind() == Kind.IDENT && !reserved(peek())) {
            alias = ident("table alias");
        }
        return new TableRef(name, alias, pos);
    }

    private Stmt select() {
        next();
        boolean distinct = acceptKw("DISTINCT");
        List<Item> items = new ArrayList<>();
        do {
            items.add(item());
        } while (acceptSym(","));
        TableRef from = null;
        List<Join> joins = new ArrayList<>();
        if (acceptKw("FROM")) {
            from = tableRef();
            while (true) {
                boolean left;
                if (isKw("LEFT")) {
                    next();
                    left = true;
                } else if (isKw("INNER")) {
                    next();
                    left = false;
                } else if (isKw("JOIN")) {
                    left = false;
                } else {
                    break;
                }
                expectKw("JOIN");
                TableRef t = tableRef();
                expectKw("ON");
                joins.add(new Join(left, t, expr()));
            }
        }
        Expr where = acceptKw("WHERE") ? expr() : null;
        List<Col> groupBy = new ArrayList<>();
        if (acceptKw("GROUP")) {
            expectKw("BY");
            do {
                groupBy.add(columnRef());
            } while (acceptSym(","));
        }
        Expr having = acceptKw("HAVING") ? expr() : null;
        List<OrderKey> order = new ArrayList<>();
        if (acceptKw("ORDER")) {
            expectKw("BY");
            do {
                Expr e = expr();
                boolean desc = false;
                if (acceptKw("DESC")) {
                    desc = true;
                } else {
                    acceptKw("ASC");
                }
                order.add(new OrderKey(e, desc));
            } while (acceptSym(","));
        }
        Integer limit = null;
        Integer offset = null;
        if (acceptKw("LIMIT")) {
            limit = nonNegativeInt();
            if (acceptKw("OFFSET")) {
                offset = nonNegativeInt();
            }
        }
        return new Select(distinct, items, from, joins, where, groupBy, having, order, limit, offset);
    }

    private int nonNegativeInt() {
        Token t = peek();
        if (t.kind() != Kind.INT) {
            throw error("expected a non-negative integer");
        }
        next();
        try {
            return Math.toIntExact(Long.parseLong(t.text()));
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new SqlException("integer too large", t.pos());
        }
    }

    private Item item() {
        int start = peek().pos();
        if (isSym("*")) {
            next();
            return new Item(null, null, "*", true, null, start);
        }
        if (peek().kind() == Kind.IDENT && !reserved(peek()) && peekAt(1).kind() == Kind.SYM
                && peekAt(1).text().equals(".") && peekAt(2).kind() == Kind.SYM && peekAt(2).text().equals("*")) {
            String q = ident("table name");
            next();
            next();
            return new Item(null, null, q + ".*", true, q, start);
        }
        Expr e = expr();
        String text = sql.substring(start, prevEnd);
        String alias = acceptKw("AS") ? ident("alias") : null;
        return new Item(e, alias, text, false, null, start);
    }

    private Col columnRef() {
        int pos = peek().pos();
        String first = ident("column name");
        if (acceptSym(".")) {
            return new Col(first, ident("column name"), pos);
        }
        return new Col(null, first, pos);
    }

    // ---- expressions, lowest precedence first ----

    Expr expr() {
        Expr left = and();
        while (isKw("OR")) {
            int pos = next().pos();
            left = new Binary("OR", left, and(), pos);
        }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (isKw("AND")) {
            int pos = next().pos();
            left = new Binary("AND", left, not(), pos);
        }
        return left;
    }

    private Expr not() {
        if (isKw("NOT")) {
            int pos = next().pos();
            return new Unary("NOT", not(), pos);
        }
        return comparison();
    }

    private Expr comparison() {
        Expr left = concat();
        while (true) {
            Token t = peek();
            if (t.kind() == Kind.SYM && Set.of("=", "!=", "<>", "<", "<=", ">", ">=").contains(t.text())) {
                next();
                left = new Binary(t.text(), left, concat(), t.pos());
            } else if (isKw("IS")) {
                next();
                boolean neg = acceptKw("NOT");
                expectKw("NULL");
                left = new IsNull(left, neg);
            } else {
                boolean neg = false;
                if (isKw("NOT") && (isKw(peekAt(1), "LIKE") || isKw(peekAt(1), "IN") || isKw(peekAt(1), "BETWEEN"))) {
                    next();
                    neg = true;
                }
                if (acceptKw("LIKE")) {
                    left = new Like(left, concat(), neg);
                } else if (acceptKw("IN")) {
                    expectSym("(");
                    List<Expr> items = new ArrayList<>();
                    do {
                        items.add(expr());
                    } while (acceptSym(","));
                    expectSym(")");
                    left = new In(left, items, neg);
                } else if (acceptKw("BETWEEN")) {
                    Expr lo = concat();
                    expectKw("AND");
                    left = new Between(left, lo, concat(), neg);
                } else {
                    return left;
                }
            }
        }
    }

    private Expr concat() {
        Expr left = additive();
        while (isSym("||")) {
            int pos = next().pos();
            left = new Binary("||", left, additive(), pos);
        }
        return left;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (isSym("+") || isSym("-")) {
            Token op = next();
            left = new Binary(op.text(), left, multiplicative(), op.pos());
        }
        return left;
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (isSym("*") || isSym("/") || isSym("%")) {
            Token op = next();
            left = new Binary(op.text(), left, unary(), op.pos());
        }
        return left;
    }

    private Expr unary() {
        if (isSym("-")) {
            int pos = next().pos();
            return new Unary("-", unary(), pos);
        }
        return primary();
    }

    private Expr primary() {
        Token t = peek();
        switch (t.kind()) {
            case INT:
                next();
                try {
                    return new Lit(Long.parseLong(t.text()));
                } catch (NumberFormatException ex) {
                    throw new SqlException("integer literal out of range", t.pos());
                }
            case STRING:
                next();
                return new Lit(t.text());
            case SYM:
                if (t.text().equals("(")) {
                    next();
                    Expr e = expr();
                    expectSym(")");
                    return e;
                }
                throw error("expected an expression");
            case IDENT:
                return identPrimary(t);
            default:
                throw error("expected an expression");
        }
    }

    private Expr identPrimary(Token t) {
        String upper = t.text().toUpperCase(Locale.ROOT);
        if (upper.equals("NULL")) {
            next();
            return new Lit(null);
        }
        if (upper.equals("TRUE") || upper.equals("FALSE")) {
            next();
            return new Lit(upper.equals("TRUE"));
        }
        if (reserved(t)) {
            throw error("expected an expression");
        }
        if (peekAt(1).kind() == Kind.SYM && peekAt(1).text().equals("(")) {
            return call(t, upper);
        }
        return columnRef();
    }

    private Expr call(Token nameTok, String upper) {
        next();
        next();
        List<Expr> args = new ArrayList<>();
        boolean star = false;
        if (isSym("*")) {
            next();
            star = true;
        } else if (!isSym(")")) {
            do {
                args.add(expr());
            } while (acceptSym(","));
        }
        expectSym(")");
        int n = args.size();
        boolean ok = switch (upper) {
            case "COUNT" -> star || n == 1;
            case "SUM", "MIN", "MAX", "AVG", "UPPER", "LOWER", "LENGTH", "ABS" -> !star && n == 1;
            case "COALESCE" -> !star && n >= 1;
            default -> throw new SqlException("unknown function " + nameTok.text(), nameTok.pos());
        };
        if (!ok) {
            throw new SqlException("wrong arguments for " + upper, nameTok.pos());
        }
        return new Call(upper, args, star, nameTok.pos());
    }
}
