package bench.minisql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lexer, AST and parser for the mini SQL dialect. */
final class Sql {
    private Sql() { }

    // ---------------------------------------------------------------- tokens
    enum Kind { KW, ID, INT, STR, SYM, EOF }

    record Token(Kind kind, String text, long num) { }

    private static final Set<String> RESERVED = Set.of(
            "CREATE", "TABLE", "DROP", "INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE", "GROUP", "BY",
            "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "UPDATE", "SET", "DELETE", "AND", "OR", "NOT",
            "NULL", "IS", "AS", "TRUE", "FALSE", "INT", "TEXT", "COUNT", "SUM", "MIN", "MAX");

    static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                i++;
            } else if (isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < n && (isLetter(s.charAt(j)) || isDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                String w = s.substring(i, j);
                String up = w.toUpperCase(Locale.ROOT);
                if (RESERVED.contains(up)) out.add(new Token(Kind.KW, up, 0));
                else out.add(new Token(Kind.ID, w.toLowerCase(Locale.ROOT), 0));
                i = j;
            } else if (isDigit(c)) {
                int j = i + 1;
                while (j < n && isDigit(s.charAt(j))) j++;
                long v;
                try {
                    v = Long.parseLong(s.substring(i, j));
                } catch (NumberFormatException e) {
                    throw new SqlException("integer literal out of range");
                }
                out.add(new Token(Kind.INT, s.substring(i, j), v));
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
                if (!closed) throw new SqlException("unterminated string literal");
                out.add(new Token(Kind.STR, sb.toString(), 0));
                i = j;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals("<>") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("||")) {
                    out.add(new Token(Kind.SYM, two.equals("!=") ? "<>" : two, 0));
                    i += 2;
                } else if ("(),;*+-/%=<>".indexOf(c) >= 0) {
                    out.add(new Token(Kind.SYM, String.valueOf(c), 0));
                    i++;
                } else {
                    throw new SqlException("unexpected character '" + c + "'");
                }
            }
        }
        out.add(new Token(Kind.EOF, "", 0));
        return out;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ------------------------------------------------------------------- AST
    interface Expr { }

    record Lit(Object v) implements Expr { }

    static final class Col implements Expr {
        final String name;
        int idx = -1;

        Col(String name) {
            this.name = name;
        }
    }

    record Neg(Expr e) implements Expr { }

    record Not(Expr e) implements Expr { }

    record Bin(String op, Expr l, Expr r) implements Expr { }

    record IsNull(Expr e, boolean negated) implements Expr { }

    /** arg is null for COUNT(*). */
    record Agg(String fn, Expr arg) implements Expr { }

    interface Stmt { }

    record Create(String table, List<String> cols, List<Boolean> isInt) implements Stmt { }

    record Drop(String table) implements Stmt { }

    record Insert(String table, List<String> cols, List<List<Expr>> tuples) implements Stmt { }

    record Update(String table, List<String> cols, List<Expr> exprs, Expr where) implements Stmt { }

    record Delete(String table, Expr where) implements Stmt { }

    record OrderItem(Expr expr, boolean desc, Long bareInt, String bareName) { }

    record Select(boolean star, List<Expr> items, List<String> aliases, String table, Expr where,
                  List<String> groupBy, Expr having, List<OrderItem> order, Long limit, Long offset)
            implements Stmt { }

    // ---------------------------------------------------------------- parser
    static final class Parser {
        private final List<Token> toks;
        private int pos;

        Parser(List<Token> toks) {
            this.toks = toks;
        }

        private Token peek() {
            return toks.get(pos);
        }

        private Token next() {
            return toks.get(pos++);
        }

        private boolean isKw(String k) {
            Token t = peek();
            return t.kind() == Kind.KW && t.text().equals(k);
        }

        private boolean isSym(String s) {
            Token t = peek();
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
            if (!acceptKw(k)) throw err("expected " + k);
        }

        private void expectSym(String s) {
            if (!acceptSym(s)) throw err("expected '" + s + "'");
        }

        private String ident() {
            Token t = peek();
            if (t.kind() != Kind.ID) throw err("expected identifier");
            pos++;
            return t.text();
        }

        private SqlException err(String msg) {
            Token t = peek();
            return new SqlException("syntax error: " + msg + " near '" + (t.kind() == Kind.EOF ? "<end>" : t.text()) + "'");
        }

        Stmt parseStatement() {
            Stmt s;
            if (acceptKw("CREATE")) s = parseCreate();
            else if (acceptKw("DROP")) {
                expectKw("TABLE");
                s = new Drop(ident());
            } else if (acceptKw("INSERT")) s = parseInsert();
            else if (acceptKw("UPDATE")) s = parseUpdate();
            else if (acceptKw("DELETE")) {
                expectKw("FROM");
                String t = ident();
                Expr w = acceptKw("WHERE") ? expr() : null;
                s = new Delete(t, w);
            } else if (acceptKw("SELECT")) s = parseSelect();
            else throw err("expected a statement");
            acceptSym(";");
            if (peek().kind() != Kind.EOF) throw err("unexpected trailing input");
            return s;
        }

        private Stmt parseCreate() {
            expectKw("TABLE");
            String t = ident();
            expectSym("(");
            List<String> cols = new ArrayList<>();
            List<Boolean> types = new ArrayList<>();
            do {
                cols.add(ident());
                if (acceptKw("INT")) types.add(true);
                else if (acceptKw("TEXT")) types.add(false);
                else throw err("expected column type");
            } while (acceptSym(","));
            expectSym(")");
            return new Create(t, cols, types);
        }

        private Stmt parseInsert() {
            expectKw("INTO");
            String t = ident();
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
                    tuple.add(expr());
                } while (acceptSym(","));
                expectSym(")");
                tuples.add(tuple);
            } while (acceptSym(","));
            return new Insert(t, cols, tuples);
        }

        private Stmt parseUpdate() {
            String t = ident();
            expectKw("SET");
            List<String> cols = new ArrayList<>();
            List<Expr> exprs = new ArrayList<>();
            do {
                cols.add(ident());
                expectSym("=");
                exprs.add(expr());
            } while (acceptSym(","));
            Expr w = acceptKw("WHERE") ? expr() : null;
            return new Update(t, cols, exprs, w);
        }

        private Stmt parseSelect() {
            boolean star = false;
            List<Expr> items = new ArrayList<>();
            List<String> aliases = new ArrayList<>();
            if (acceptSym("*")) {
                star = true;
            } else {
                do {
                    items.add(expr());
                    aliases.add(acceptKw("AS") ? ident() : null);
                } while (acceptSym(","));
            }
            expectKw("FROM");
            String table = ident();
            Expr where = acceptKw("WHERE") ? expr() : null;
            List<String> groupBy = null;
            if (acceptKw("GROUP")) {
                expectKw("BY");
                groupBy = new ArrayList<>();
                do {
                    groupBy.add(ident());
                } while (acceptSym(","));
            }
            Expr having = acceptKw("HAVING") ? expr() : null;
            List<OrderItem> order = new ArrayList<>();
            if (acceptKw("ORDER")) {
                expectKw("BY");
                do {
                    int start = pos;
                    Expr e = expr();
                    Long bareInt = null;
                    String bareName = null;
                    if (pos - start == 1) {
                        Token t = toks.get(start);
                        if (t.kind() == Kind.INT) bareInt = t.num();
                        else if (t.kind() == Kind.ID) bareName = t.text();
                    }
                    boolean desc = false;
                    if (acceptKw("DESC")) desc = true;
                    else acceptKw("ASC");
                    order.add(new OrderItem(e, desc, bareInt, bareName));
                } while (acceptSym(","));
            }
            Long limit = null;
            Long offset = null;
            if (acceptKw("LIMIT")) {
                limit = intLiteral();
                if (acceptKw("OFFSET")) offset = intLiteral();
            }
            return new Select(star, items, aliases, table, where, groupBy, having, order, limit, offset);
        }

        private long intLiteral() {
            Token t = peek();
            if (t.kind() != Kind.INT) throw err("expected integer");
            pos++;
            return t.num();
        }

        // ------------------------------------------------------ expressions
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
            if (acceptKw("NOT")) return new Not(not());
            return cmp();
        }

        private Expr cmp() {
            Expr l = add();
            Token t = peek();
            if (t.kind() == Kind.SYM) {
                switch (t.text()) {
                    case "=", "<>", "<", "<=", ">", ">=" -> {
                        pos++;
                        return new Bin(t.text(), l, add());
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

        private Expr add() {
            Expr l = mul();
            while (true) {
                Token t = peek();
                if (t.kind() == Kind.SYM && (t.text().equals("+") || t.text().equals("-") || t.text().equals("||"))) {
                    pos++;
                    l = new Bin(t.text(), l, mul());
                } else {
                    return l;
                }
            }
        }

        private Expr mul() {
            Expr l = unary();
            while (true) {
                Token t = peek();
                if (t.kind() == Kind.SYM && (t.text().equals("*") || t.text().equals("/") || t.text().equals("%"))) {
                    pos++;
                    l = new Bin(t.text(), l, unary());
                } else {
                    return l;
                }
            }
        }

        private Expr unary() {
            if (acceptSym("-")) return new Neg(unary());
            return primary();
        }

        private Expr primary() {
            Token t = peek();
            switch (t.kind()) {
                case INT:
                    pos++;
                    return new Lit(t.num());
                case STR:
                    pos++;
                    return new Lit(t.text());
                case ID:
                    pos++;
                    return new Col(t.text());
                case SYM:
                    if (t.text().equals("(")) {
                        pos++;
                        Expr e = expr();
                        expectSym(")");
                        return e;
                    }
                    throw err("unexpected symbol");
                case KW:
                    switch (t.text()) {
                        case "NULL":
                            pos++;
                            return new Lit(null);
                        case "TRUE":
                            pos++;
                            return new Lit(Boolean.TRUE);
                        case "FALSE":
                            pos++;
                            return new Lit(Boolean.FALSE);
                        case "COUNT", "SUM", "MIN", "MAX": {
                            pos++;
                            expectSym("(");
                            Expr arg;
                            if (t.text().equals("COUNT") && acceptSym("*")) arg = null;
                            else arg = expr();
                            expectSym(")");
                            return new Agg(t.text(), arg);
                        }
                        default:
                            throw err("unexpected keyword");
                    }
                default:
                    throw err("unexpected end of statement");
            }
        }
    }
}
