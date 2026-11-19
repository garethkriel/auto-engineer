package bench.minisql;

import java.util.ArrayList;
import java.util.List;

/** Recursive descent parser. */
final class Parser {
    private final List<Tok> toks;
    private int pos;

    private Parser(List<Tok> toks) { this.toks = toks; }

    static Ast.Stmt parse(String sql) {
        Parser p = new Parser(Lexer.lex(sql));
        Ast.Stmt s = p.statement();
        p.acceptSym(";");
        if (p.peek().kind() != Tok.Kind.EOF) {
            throw p.err("unexpected trailing input");
        }
        return s;
    }

    private Tok peek() { return toks.get(pos); }

    private Tok next() {
        Tok t = toks.get(pos);
        if (t.kind() != Tok.Kind.EOF) {
            pos++;
        }
        return t;
    }

    private SqlException err(String m) {
        Tok t = peek();
        return new SqlException("syntax error: " + m + " near '" + t.text() + "'");
    }

    private boolean isKw(String k) {
        Tok t = peek();
        return t.kind() == Tok.Kind.KW && t.text().equals(k);
    }

    private boolean acceptKw(String k) {
        if (isKw(k)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expectKw(String k) {
        if (!acceptKw(k)) {
            throw err("expected " + k);
        }
    }

    private boolean isSym(String s) {
        Tok t = peek();
        return t.kind() == Tok.Kind.SYM && t.text().equals(s);
    }

    private boolean acceptSym(String s) {
        if (isSym(s)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expectSym(String s) {
        if (!acceptSym(s)) {
            throw err("expected " + s);
        }
    }

    private String ident() {
        Tok t = peek();
        if (t.kind() != Tok.Kind.ID) {
            throw err("expected identifier");
        }
        pos++;
        return t.text();
    }

    private Ast.Stmt statement() {
        if (acceptKw("CREATE")) {
            expectKw("TABLE");
            String name = ident();
            expectSym("(");
            List<Ast.ColDef> cols = new ArrayList<>();
            do {
                String c = ident();
                boolean isInt;
                if (acceptKw("INT")) {
                    isInt = true;
                } else if (acceptKw("TEXT")) {
                    isInt = false;
                } else {
                    throw err("expected column type");
                }
                cols.add(new Ast.ColDef(c, isInt));
            } while (acceptSym(","));
            expectSym(")");
            return new Ast.Create(name, cols);
        }
        if (acceptKw("DROP")) {
            expectKw("TABLE");
            return new Ast.Drop(ident());
        }
        if (acceptKw("INSERT")) {
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
            List<List<Expr>> rows = new ArrayList<>();
            do {
                expectSym("(");
                List<Expr> r = new ArrayList<>();
                do {
                    r.add(expr());
                } while (acceptSym(","));
                expectSym(")");
                rows.add(r);
            } while (acceptSym(","));
            return new Ast.Insert(name, cols, rows);
        }
        if (acceptKw("UPDATE")) {
            String name = ident();
            expectKw("SET");
            List<String> cols = new ArrayList<>();
            List<Expr> vals = new ArrayList<>();
            do {
                cols.add(ident());
                expectSym("=");
                vals.add(expr());
            } while (acceptSym(","));
            Expr where = acceptKw("WHERE") ? expr() : null;
            return new Ast.Update(name, cols, vals, where);
        }
        if (acceptKw("DELETE")) {
            expectKw("FROM");
            String name = ident();
            Expr where = acceptKw("WHERE") ? expr() : null;
            return new Ast.Delete(name, where);
        }
        if (acceptKw("SELECT")) {
            return select();
        }
        throw err("expected a statement");
    }

    private Ast.Select select() {
        List<Ast.Item> items = null;
        if (acceptSym("*")) {
            expectKw("FROM");
        } else {
            items = new ArrayList<>();
            do {
                Expr e = expr();
                String alias = acceptKw("AS") ? ident() : null;
                items.add(new Ast.Item(e, alias));
            } while (acceptSym(","));
            expectKw("FROM");
        }
        String table = ident();
        Expr where = acceptKw("WHERE") ? expr() : null;
        List<String> groupBy = new ArrayList<>();
        if (acceptKw("GROUP")) {
            expectKw("BY");
            do {
                groupBy.add(ident());
            } while (acceptSym(","));
        }
        Expr having = acceptKw("HAVING") ? expr() : null;
        List<Ast.OrderItem> order = new ArrayList<>();
        if (acceptKw("ORDER")) {
            expectKw("BY");
            do {
                int start = pos;
                Expr e = expr();
                Long p = null;
                String nm = null;
                if (pos == start + 1) {
                    Tok t = toks.get(start);
                    if (t.kind() == Tok.Kind.NUM) {
                        p = t.num();
                    } else if (t.kind() == Tok.Kind.ID) {
                        nm = t.text();
                    }
                }
                boolean desc = false;
                if (acceptKw("DESC")) {
                    desc = true;
                } else {
                    acceptKw("ASC");
                }
                order.add(new Ast.OrderItem(e, p, nm, desc));
            } while (acceptSym(","));
        }
        long limit = -1;
        long offset = -1;
        if (acceptKw("LIMIT")) {
            limit = number();
            if (acceptKw("OFFSET")) {
                offset = number();
            }
        }
        return new Ast.Select(items, table, where, groupBy, having, order, limit, offset);
    }

    private long number() {
        Tok t = peek();
        if (t.kind() != Tok.Kind.NUM) {
            throw err("expected integer");
        }
        pos++;
        return t.num();
    }

    Expr expr() {
        Expr l = and();
        while (acceptKw("OR")) {
            l = new Expr.Bin("OR", l, and());
        }
        return l;
    }

    private Expr and() {
        Expr l = not();
        while (acceptKw("AND")) {
            l = new Expr.Bin("AND", l, not());
        }
        return l;
    }

    private Expr not() {
        if (acceptKw("NOT")) {
            return new Expr.Not(not());
        }
        return cmp();
    }

    private Expr cmp() {
        Expr l = add();
        Tok t = peek();
        if (t.kind() == Tok.Kind.SYM) {
            switch (t.text()) {
                case "=", "<>", "<", "<=", ">", ">=" -> {
                    pos++;
                    return new Expr.Bin(t.text(), l, add());
                }
                default -> { }
            }
        } else if (isKw("IS")) {
            pos++;
            boolean neg = acceptKw("NOT");
            expectKw("NULL");
            return new Expr.IsNull(l, neg);
        }
        return l;
    }

    private Expr add() {
        Expr l = mul();
        while (isSym("+") || isSym("-") || isSym("||")) {
            String op = next().text();
            l = new Expr.Bin(op, l, mul());
        }
        return l;
    }

    private Expr mul() {
        Expr l = unary();
        while (isSym("*") || isSym("/") || isSym("%")) {
            String op = next().text();
            l = new Expr.Bin(op, l, unary());
        }
        return l;
    }

    private Expr unary() {
        if (acceptSym("-")) {
            return new Expr.Neg(unary());
        }
        return primary();
    }

    private Expr primary() {
        Tok t = peek();
        switch (t.kind()) {
            case NUM -> {
                pos++;
                return new Expr.Lit(t.num());
            }
            case STR -> {
                pos++;
                return new Expr.Lit(t.text());
            }
            case ID -> {
                pos++;
                return new Expr.Col(t.text());
            }
            case SYM -> {
                if (t.text().equals("(")) {
                    pos++;
                    Expr e = expr();
                    expectSym(")");
                    return e;
                }
                throw err("unexpected symbol");
            }
            case KW -> {
                switch (t.text()) {
                    case "NULL" -> {
                        pos++;
                        return new Expr.Lit(null);
                    }
                    case "TRUE" -> {
                        pos++;
                        return new Expr.Lit(Boolean.TRUE);
                    }
                    case "FALSE" -> {
                        pos++;
                        return new Expr.Lit(Boolean.FALSE);
                    }
                    case "COUNT", "SUM", "MIN", "MAX" -> {
                        pos++;
                        expectSym("(");
                        Expr arg;
                        if (t.text().equals("COUNT") && acceptSym("*")) {
                            arg = null;
                        } else {
                            arg = expr();
                        }
                        expectSym(")");
                        return new Expr.Agg(t.text(), arg);
                    }
                    default -> throw err("unexpected keyword");
                }
            }
            default -> throw err("unexpected end of input");
        }
    }
}
