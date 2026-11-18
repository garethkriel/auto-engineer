package bench.minisql;

import bench.minisql.Expr.Agg;
import bench.minisql.Expr.Bin;
import bench.minisql.Expr.Col;
import bench.minisql.Expr.IsNull;
import bench.minisql.Expr.Lit;
import bench.minisql.Expr.Neg;
import bench.minisql.Expr.Not;
import bench.minisql.Expr.Op;
import bench.minisql.Lexer.Kind;
import bench.minisql.Lexer.Tok;
import java.util.ArrayList;
import java.util.List;

/** Recursive-descent parser producing statement descriptions. */
final class Parser {

    // ---- statements ----
    sealed interface Stmt permits Create, Drop, Insert, Update, Delete, Select { }

    record ColDef(String name, boolean isInt) { }

    record Create(String table, List<ColDef> cols) implements Stmt { }

    record Drop(String table) implements Stmt { }

    record Insert(String table, List<String> cols, List<List<Expr>> tuples) implements Stmt { }

    record Assign(String col, Expr value) { }

    record Update(String table, List<Assign> sets, Expr where) implements Stmt { }

    record Delete(String table, Expr where) implements Stmt { }

    record Item(Expr expr, String alias) { }

    /** itemsAll true means "*". */
    record Order(Expr expr, int bareInt, String bareName, boolean desc) { }

    record Select(boolean star, List<Item> items, String table, Expr where, List<String> groupBy, Expr having,
                  List<Order> order, Long limit, Long offset) implements Stmt { }

    private final List<Tok> toks;
    private int pos;

    Parser(List<Tok> toks) {
        this.toks = toks;
    }

    private static SqlException err(String m) {
        return new SqlException(m);
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
            throw err("expected " + k + " but found '" + peek().text() + "'");
        }
    }

    private void expectSym(String s) {
        if (!acceptSym(s)) {
            throw err("expected '" + s + "' but found '" + peek().text() + "'");
        }
    }

    private String ident() {
        Tok t = next();
        if (t.kind() != Kind.IDENT) {
            throw err("expected identifier but found '" + t.text() + "'");
        }
        return t.text();
    }

    Stmt parseStatement() {
        Stmt s;
        if (acceptKw("CREATE")) {
            s = create();
        } else if (acceptKw("DROP")) {
            expectKw("TABLE");
            s = new Drop(ident());
        } else if (acceptKw("INSERT")) {
            s = insert();
        } else if (acceptKw("UPDATE")) {
            s = update();
        } else if (acceptKw("DELETE")) {
            expectKw("FROM");
            String t = ident();
            Expr w = acceptKw("WHERE") ? expr() : null;
            s = new Delete(t, w);
        } else if (acceptKw("SELECT")) {
            s = select();
        } else {
            throw err("unknown statement");
        }
        acceptSym(";");
        if (peek().kind() != Kind.EOF) {
            throw err("unexpected input after statement: '" + peek().text() + "'");
        }
        return s;
    }

    private Stmt create() {
        expectKw("TABLE");
        String name = ident();
        expectSym("(");
        List<ColDef> cols = new ArrayList<>();
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
            cols.add(new ColDef(c, isInt));
        } while (acceptSym(","));
        expectSym(")");
        return new Create(name, cols);
    }

    private Stmt insert() {
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
            List<Expr> vals = new ArrayList<>();
            do {
                vals.add(expr());
            } while (acceptSym(","));
            expectSym(")");
            tuples.add(vals);
        } while (acceptSym(","));
        return new Insert(table, cols, tuples);
    }

    private Stmt update() {
        String table = ident();
        expectKw("SET");
        List<Assign> sets = new ArrayList<>();
        do {
            String c = ident();
            expectSym("=");
            sets.add(new Assign(c, expr()));
        } while (acceptSym(","));
        Expr w = acceptKw("WHERE") ? expr() : null;
        return new Update(table, sets, w);
    }

    private Stmt select() {
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
        List<String> group = null;
        if (acceptKw("GROUP")) {
            expectKw("BY");
            group = new ArrayList<>();
            do {
                group.add(ident());
            } while (acceptSym(","));
        }
        Expr having = acceptKw("HAVING") ? expr() : null;
        List<Order> order = new ArrayList<>();
        if (acceptKw("ORDER")) {
            expectKw("BY");
            do {
                int start = pos;
                Expr e = expr();
                int bareInt = -1;
                String bareName = null;
                if (pos == start + 1) {
                    Tok t = toks.get(start);
                    if (t.kind() == Kind.INT) {
                        bareInt = (int) Math.min(t.num(), Integer.MAX_VALUE);
                        if (bareInt == 0) {
                            bareInt = 0;
                        }
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
        Long limit = null;
        Long offset = null;
        if (acceptKw("LIMIT")) {
            limit = intLiteral();
            if (acceptKw("OFFSET")) {
                offset = intLiteral();
            }
        }
        return new Select(star, items, table, where, group, having, order, limit, offset);
    }

    private long intLiteral() {
        Tok t = next();
        if (t.kind() != Kind.INT) {
            throw err("expected integer");
        }
        return t.num();
    }

    // ---- expressions ----
    Expr expr() {
        Expr l = and();
        while (acceptKw("OR")) {
            l = new Bin(Op.OR, l, and());
        }
        return l;
    }

    private Expr and() {
        Expr l = not();
        while (acceptKw("AND")) {
            l = new Bin(Op.AND, l, not());
        }
        return l;
    }

    private Expr not() {
        if (acceptKw("NOT")) {
            return new Not(not());
        }
        return cmp();
    }

    private Expr cmp() {
        Expr l = add();
        Tok t = peek();
        if (t.kind() == Kind.SYM) {
            Op op = switch (t.text()) {
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

    private Expr add() {
        Expr l = mul();
        while (true) {
            if (acceptSym("+")) {
                l = new Bin(Op.ADD, l, mul());
            } else if (acceptSym("-")) {
                l = new Bin(Op.SUB, l, mul());
            } else if (acceptSym("||")) {
                l = new Bin(Op.CONCAT, l, mul());
            } else {
                return l;
            }
        }
    }

    private Expr mul() {
        Expr l = unary();
        while (true) {
            if (acceptSym("*")) {
                l = new Bin(Op.MUL, l, unary());
            } else if (acceptSym("/")) {
                l = new Bin(Op.DIV, l, unary());
            } else if (acceptSym("%")) {
                l = new Bin(Op.MOD, l, unary());
            } else {
                return l;
            }
        }
    }

    private Expr unary() {
        if (acceptSym("-")) {
            return new Neg(unary());
        }
        return primary();
    }

    private Expr primary() {
        Tok t = next();
        switch (t.kind()) {
            case INT:
                return new Lit(t.num());
            case STR:
                return new Lit(t.text());
            case IDENT:
                return new Col(t.text());
            case KW:
                switch (t.text()) {
                    case "NULL":
                        return new Lit(null);
                    case "TRUE":
                        return new Lit(Boolean.TRUE);
                    case "FALSE":
                        return new Lit(Boolean.FALSE);
                    case "COUNT", "SUM", "MIN", "MAX":
                        expectSym("(");
                        Expr arg;
                        if (t.text().equals("COUNT") && acceptSym("*")) {
                            arg = null;
                        } else {
                            arg = expr();
                        }
                        expectSym(")");
                        return new Agg(t.text(), arg);
                    default:
                        throw err("unexpected keyword " + t.text());
                }
            case SYM:
                if (t.text().equals("(")) {
                    Expr e = expr();
                    expectSym(")");
                    return e;
                }
                throw err("unexpected '" + t.text() + "'");
            default:
                throw err("unexpected end of statement");
        }
    }
}
