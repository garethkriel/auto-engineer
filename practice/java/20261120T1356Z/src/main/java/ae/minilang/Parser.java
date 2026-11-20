package ae.minilang;

import ae.minilang.Ast.*;
import ae.minilang.Lexer.Token;
import ae.minilang.Lexer.Type;
import java.util.ArrayList;
import java.util.List;

/** Recursive-descent parser with precedence climbing for binary operators. */
final class Parser {
    private static final String MIN_DIGITS = "9223372036854775808";

    private final List<Token> toks;
    private int p;

    private Parser(List<Token> toks) {
        this.toks = toks;
    }

    static Program parse(String src) {
        return new Parser(Lexer.lex(src)).program();
    }

    private Token peek() {
        return toks.get(p);
    }

    private Token next() {
        Token t = toks.get(p);
        if (t.type() != Type.EOF) {
            p++;
        }
        return t;
    }

    private LangException err(Token t, String msg) {
        return Lexer.error(t.line(), t.col(), msg + " but found '" + t.text() + "'");
    }

    private Token expect(String sym) {
        Token t = peek();
        if (!t.is(sym)) {
            throw err(t, "expected '" + sym + "'");
        }
        return next();
    }

    private String ident() {
        Token t = peek();
        if (t.type() != Type.ID || Lexer.KEYWORDS.contains(t.text())) {
            throw err(t, "expected a name");
        }
        return next().text();
    }

    private Program program() {
        List<Func> funcs = new ArrayList<>();
        List<Stmt> main = new ArrayList<>();
        while (peek().type() != Type.EOF) {
            if (peek().isKeyword("fn")) {
                funcs.add(func());
            } else {
                main.add(stmt());
            }
        }
        return new Program(List.copyOf(funcs), List.copyOf(main));
    }

    private Func func() {
        Token kw = next();
        Token nameTok = peek();
        String name = ident();
        expect("(");
        List<String> params = new ArrayList<>();
        if (!peek().is(")")) {
            params.add(ident());
            while (peek().is(",")) {
                next();
                params.add(ident());
            }
        }
        expect(")");
        return new Func(name, List.copyOf(params), block(), new Pos(nameTok.line(), nameTok.col()));
    }

    private List<Stmt> block() {
        expect("{");
        List<Stmt> body = new ArrayList<>();
        while (!peek().is("}")) {
            if (peek().type() == Type.EOF) {
                throw err(peek(), "expected '}'");
            }
            body.add(stmt());
        }
        next();
        return List.copyOf(body);
    }

    private Stmt stmt() {
        Token t = peek();
        if (t.isKeyword("let")) {
            next();
            Token nameTok = peek();
            String name = ident();
            expect("=");
            Expr init = expr();
            expect(";");
            return new Let(name, init, new Pos(nameTok.line(), nameTok.col()));
        }
        if (t.isKeyword("if")) {
            return ifStmt();
        }
        if (t.isKeyword("while")) {
            next();
            expect("(");
            Expr c = expr();
            expect(")");
            return new While(c, block());
        }
        if (t.isKeyword("return")) {
            next();
            Expr v = peek().is(";") ? null : expr();
            expect(";");
            return new Return(v);
        }
        if (t.isKeyword("print")) {
            next();
            Expr v = expr();
            expect(";");
            return new Print(v);
        }
        if (t.is("{")) {
            return new Block(block());
        }
        if (t.type() == Type.ID && !Lexer.KEYWORDS.contains(t.text()) && toks.get(p + 1).is("=")) {
            next();
            next();
            Expr v = expr();
            expect(";");
            return new Assign(t.text(), v, new Pos(t.line(), t.col()));
        }
        if (t.isKeyword("fn")) {
            throw err(t, "functions are only allowed at top level; expected a statement");
        }
        Expr e = expr();
        expect(";");
        return new ExprStmt(e);
    }

    private Stmt ifStmt() {
        next();
        expect("(");
        Expr c = expr();
        expect(")");
        List<Stmt> thenBody = block();
        List<Stmt> elseBody = List.of();
        if (peek().isKeyword("else")) {
            next();
            elseBody = peek().isKeyword("if") ? List.of(ifStmt()) : block();
        }
        return new If(c, thenBody, elseBody);
    }

    // precedence levels, loosest first
    private static final BinOp[][] LEVELS = {
        {BinOp.OR}, {BinOp.AND}, {BinOp.EQ, BinOp.NE}, {BinOp.LT, BinOp.LE, BinOp.GT, BinOp.GE},
        {BinOp.ADD, BinOp.SUB}, {BinOp.MUL, BinOp.DIV, BinOp.MOD}};

    private Expr expr() {
        return level(0);
    }

    private Expr level(int lvl) {
        if (lvl == LEVELS.length) {
            return unary();
        }
        Expr left = level(lvl + 1);
        while (true) {
            BinOp found = null;
            Token t = peek();
            for (BinOp op : LEVELS[lvl]) {
                if (t.is(op.symbol)) {
                    found = op;
                }
            }
            if (found == null) {
                return left;
            }
            next();
            left = new Binary(found, left, level(lvl + 1));
        }
    }

    private Expr unary() {
        Token t = peek();
        if (t.is("-")) {
            next();
            Token n = peek();
            if (n.type() == Type.NUM && n.text().equals(MIN_DIGITS)) {
                next();
                return new Num(Long.MIN_VALUE);
            }
            return new Unary(UnOp.NEG, unary());
        }
        if (t.is("!")) {
            next();
            return new Unary(UnOp.NOT, unary());
        }
        return primary();
    }

    private Expr primary() {
        Token t = next();
        if (t.type() == Type.NUM) {
            try {
                return new Num(Long.parseLong(t.text()));
            } catch (NumberFormatException e) {
                throw Lexer.error(t.line(), t.col(), "integer literal out of range: " + t.text());
            }
        }
        if (t.is("(")) {
            Expr e = expr();
            expect(")");
            return e;
        }
        if (t.type() == Type.ID && !Lexer.KEYWORDS.contains(t.text())) {
            if (peek().is("(")) {
                next();
                List<Expr> args = new ArrayList<>();
                if (!peek().is(")")) {
                    args.add(expr());
                    while (peek().is(",")) {
                        next();
                        args.add(expr());
                    }
                }
                expect(")");
                return new Call(t.text(), List.copyOf(args), new Pos(t.line(), t.col()));
            }
            return new Var(t.text(), new Pos(t.line(), t.col()));
        }
        throw err(t, "expected an expression");
    }
}
