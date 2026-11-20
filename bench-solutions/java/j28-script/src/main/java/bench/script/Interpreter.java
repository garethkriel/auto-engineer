package bench.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tree-walking interpreter for a small language. */
public final class Interpreter {
    private static final int MAX_DEPTH = 20_000;
    private static final long STACK_BYTES = 768L * 1024 * 1024;

    private Interpreter() {
    }

    public static List<String> run(String source) {
        if (source == null) {
            throw new IllegalArgumentException("source is null");
        }
        Object[] result = new Object[1];
        Thread t = new Thread(null, () -> {
            try {
                result[0] = runInternal(source);
            } catch (Throwable e) {
                result[0] = e;
            }
        }, "script", STACK_BYTES);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (result[0] instanceof RuntimeException re) {
            throw re;
        }
        if (result[0] instanceof Error er) {
            throw er;
        }
        if (result[0] instanceof Throwable th) {
            throw new IllegalStateException(th);
        }
        @SuppressWarnings("unchecked")
        List<String> out = (List<String>) result[0];
        return out;
    }

    private static List<String> runInternal(String source) {
        List<Token> tokens = new Lexer(source).lex();
        List<Stmt> program = new Parser(tokens).parseProgram();
        Runtime rt = new Runtime();
        Env global = new Env(null);
        try {
            for (Stmt s : program) {
                if (s.exec(rt, global)) {
                    break;
                }
            }
        } catch (StackOverflowError so) {
            throw new ScriptException(rt.lastLine, rt.lastCol, "call stack overflow");
        }
        return rt.out;
    }

    // ---------------------------------------------------------------- tokens

    private enum T {
        INT, IDENT, LET, FN, IF, ELSE, WHILE, RETURN, PRINT, TRUE, FALSE, NIL,
        PLUS, MINUS, STAR, SLASH, PERCENT, LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMI,
        ASSIGN, EQ, NE, LT, LE, GT, GE, AND, OR, BANG, ERROR, EOF
    }

    private record Token(T type, String text, long value, int line, int col) {
    }

    private static final Map<String, T> KEYWORDS = Map.ofEntries(
            Map.entry("let", T.LET), Map.entry("fn", T.FN), Map.entry("if", T.IF),
            Map.entry("else", T.ELSE), Map.entry("while", T.WHILE), Map.entry("return", T.RETURN),
            Map.entry("print", T.PRINT), Map.entry("true", T.TRUE), Map.entry("false", T.FALSE),
            Map.entry("nil", T.NIL));

    private static final class Lexer {
        private final String src;
        private int pos;
        private int line = 1;
        private int col = 1;
        private final List<Token> out = new ArrayList<>();

        Lexer(String src) {
            this.src = src;
        }

        private void advance() {
            if (src.charAt(pos) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            pos++;
        }

        private void add(T type, String text, int l, int c) {
            out.add(new Token(type, text, 0, l, c));
        }

        List<Token> lex() {
            int n = src.length();
            while (pos < n) {
                char c = src.charAt(pos);
                int l = line;
                int cl = col;
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    advance();
                } else if (c == '/' && pos + 1 < n && src.charAt(pos + 1) == '/') {
                    while (pos < n && src.charAt(pos) != '\n') {
                        advance();
                    }
                } else if (c >= '0' && c <= '9') {
                    int s = pos;
                    while (pos < n && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
                        advance();
                    }
                    String text = src.substring(s, pos);
                    try {
                        out.add(new Token(T.INT, text, Long.parseLong(text), l, cl));
                    } catch (NumberFormatException e) {
                        add(T.ERROR, "integer literal too large", l, cl);
                        break;
                    }
                } else if (isIdStart(c)) {
                    int s = pos;
                    while (pos < n && (isIdStart(src.charAt(pos))
                            || (src.charAt(pos) >= '0' && src.charAt(pos) <= '9'))) {
                        advance();
                    }
                    String text = src.substring(s, pos);
                    add(KEYWORDS.getOrDefault(text, T.IDENT), text, l, cl);
                } else {
                    T two = null;
                    char d = pos + 1 < n ? src.charAt(pos + 1) : '\0';
                    switch (c) {
                        case '=' -> two = d == '=' ? T.EQ : null;
                        case '!' -> two = d == '=' ? T.NE : null;
                        case '<' -> two = d == '=' ? T.LE : null;
                        case '>' -> two = d == '=' ? T.GE : null;
                        case '&' -> two = d == '&' ? T.AND : null;
                        case '|' -> two = d == '|' ? T.OR : null;
                        default -> two = null;
                    }
                    if (two != null) {
                        advance();
                        advance();
                        add(two, "", l, cl);
                        continue;
                    }
                    T one = switch (c) {
                        case '+' -> T.PLUS;
                        case '-' -> T.MINUS;
                        case '*' -> T.STAR;
                        case '/' -> T.SLASH;
                        case '%' -> T.PERCENT;
                        case '(' -> T.LPAREN;
                        case ')' -> T.RPAREN;
                        case '{' -> T.LBRACE;
                        case '}' -> T.RBRACE;
                        case ',' -> T.COMMA;
                        case ';' -> T.SEMI;
                        case '=' -> T.ASSIGN;
                        case '<' -> T.LT;
                        case '>' -> T.GT;
                        case '!' -> T.BANG;
                        default -> null;
                    };
                    if (one == null) {
                        add(T.ERROR, "unexpected character", l, cl);
                        break;
                    }
                    advance();
                    add(one, "", l, cl);
                }
            }
            if (out.isEmpty() || out.get(out.size() - 1).type() != T.ERROR) {
                out.add(new Token(T.EOF, "", 0, line, col));
            }
            return out;
        }

        private static boolean isIdStart(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }
    }

    // ---------------------------------------------------------------- runtime

    private static final class Nil {
        static final Nil INSTANCE = new Nil();
    }

    private static final class Closure {
        final String name;
        final List<String> params;
        final List<Stmt> body;
        final Env env;

        Closure(String name, List<String> params, List<Stmt> body, Env env) {
            this.name = name;
            this.params = params;
            this.body = body;
            this.env = env;
        }
    }

    private static final class Runtime {
        final List<String> out = new ArrayList<>();
        int depth;
        Object returnValue;
        int lastLine = 1;
        int lastCol = 1;
    }

    private static final class Env {
        final Env parent;
        final HashMap<String, Object> vars = new HashMap<>(8);

        Env(Env parent) {
            this.parent = parent;
        }

        Object lookup(String name) {
            for (Env e = this; e != null; e = e.parent) {
                Object v = e.vars.get(name);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }

        boolean assign(String name, Object value) {
            for (Env e = this; e != null; e = e.parent) {
                if (e.vars.containsKey(name)) {
                    e.vars.put(name, value);
                    return true;
                }
            }
            return false;
        }
    }

    private static ScriptException err(int line, int col, String msg) {
        return new ScriptException(line, col, msg);
    }

    private static String show(Object v) {
        if (v instanceof Closure c) {
            return c.name == null ? "<fn>" : "<fn " + c.name + ">";
        }
        return String.valueOf(v instanceof Nil ? "nil" : v);
    }

    // ---------------------------------------------------------------- AST

    private abstract static class Expr {
        final int line;
        final int col;

        Expr(int line, int col) {
            this.line = line;
            this.col = col;
        }

        abstract Object eval(Runtime rt, Env env);
    }

    private abstract static class Stmt {
        final int line;
        final int col;

        Stmt(int line, int col) {
            this.line = line;
            this.col = col;
        }

        /** Returns true if a return statement was executed (value in rt.returnValue). */
        abstract boolean exec(Runtime rt, Env env);
    }

    private static final class Lit extends Expr {
        final Object value;

        Lit(Object value, int l, int c) {
            super(l, c);
            this.value = value;
        }

        @Override
        Object eval(Runtime rt, Env env) {
            return value;
        }
    }

    private static final class Var extends Expr {
        final String name;

        Var(String name, int l, int c) {
            super(l, c);
            this.name = name;
        }

        @Override
        Object eval(Runtime rt, Env env) {
            Object v = env.lookup(name);
            if (v == null) {
                throw err(line, col, "undefined variable '" + name + "'");
            }
            return v;
        }
    }

    private static final class FnExpr extends Expr {
        final List<String> params;
        final List<Stmt> body;

        FnExpr(List<String> params, List<Stmt> body, int l, int c) {
            super(l, c);
            this.params = params;
            this.body = body;
        }

        @Override
        Object eval(Runtime rt, Env env) {
            return new Closure(null, params, body, env);
        }
    }

    private static final class Unary extends Expr {
        final T op;
        final Expr operand;

        Unary(T op, Expr operand, int l, int c) {
            super(l, c);
            this.op = op;
            this.operand = operand;
        }

        @Override
        Object eval(Runtime rt, Env env) {
            Object v = operand.eval(rt, env);
            if (op == T.MINUS) {
                if (v instanceof Long x) {
                    return -x;
                }
                throw err(line, col, "operand of '-' must be an integer");
            }
            if (v instanceof Boolean b) {
                return !b;
            }
            throw err(line, col, "operand of '!' must be a boolean");
        }
    }

    private static final class Binary extends Expr {
        final T op;
        final Expr left;
        final Expr right;

        Binary(T op, Expr left, Expr right, int l, int c) {
            super(l, c);
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        Object eval(Runtime rt, Env env) {
            Object a = left.eval(rt, env);
            if (op == T.AND || op == T.OR) {
                if (!(a instanceof Boolean ab)) {
                    throw err(line, col, "operands must be booleans");
                }
                if (op == T.AND ? !ab : ab) {
                    return ab;
                }
                Object b = right.eval(rt, env);
                if (!(b instanceof Boolean)) {
                    throw err(line, col, "operands must be booleans");
                }
                return b;
            }
            Object b = right.eval(rt, env);
            if (op == T.EQ) {
                return a.equals(b);
            }
            if (op == T.NE) {
                return !a.equals(b);
            }
            if (!(a instanceof Long x) || !(b instanceof Long y)) {
                throw err(line, col, "operands must be integers");
            }
            switch (op) {
                case PLUS:
                    return x + y;
                case MINUS:
                    return x - y;
                case STAR:
                    return x * y;
                case SLASH:
                    if (y == 0) {
                        throw err(line, col, "division by zero");
                    }
                    return x / y;
                case PERCENT:
                    if (y == 0) {
                        throw err(line, col, "division by zero");
                    }
                    return x % y;
                case LT:
                    return x < y;
                case LE:
                    return x <= y;
                case GT:
                    return x > y;
                case GE:
                    return x >= y;
                default:
                    throw new IllegalStateException("bad operator " + op);
            }
        }
    }

    private static final class Call extends Expr {
        final Expr callee;
        final List<Expr> args;

        Call(Expr callee, List<Expr> args, int l, int c) {
            super(l, c);
            this.callee = callee;
            this.args = args;
        }

        @Override
        Object eval(Runtime rt, Env env) {
            Object f = callee.eval(rt, env);
            Object[] vals = new Object[args.size()];
            for (int i = 0; i < vals.length; i++) {
                vals[i] = args.get(i).eval(rt, env);
            }
            if (!(f instanceof Closure c)) {
                throw err(line, col, "not a function");
            }
            if (c.params.size() != vals.length) {
                throw err(line, col, "expected " + c.params.size() + " arguments but got " + vals.length);
            }
            if (rt.depth >= MAX_DEPTH) {
                throw err(line, col, "call depth exceeded");
            }
            rt.lastLine = line;
            rt.lastCol = col;
            Env scope = new Env(c.env);
            for (int i = 0; i < vals.length; i++) {
                scope.vars.put(c.params.get(i), vals[i]);
            }
            rt.depth++;
            try {
                for (Stmt s : c.body) {
                    if (s.exec(rt, scope)) {
                        Object r = rt.returnValue;
                        rt.returnValue = null;
                        return r;
                    }
                }
            } finally {
                rt.depth--;
            }
            return Nil.INSTANCE;
        }
    }

    private static final class LetStmt extends Stmt {
        final String name;
        final int nameLine;
        final int nameCol;
        final Expr init;

        LetStmt(String name, int nl, int nc, Expr init, int l, int c) {
            super(l, c);
            this.name = name;
            this.nameLine = nl;
            this.nameCol = nc;
            this.init = init;
        }

        @Override
        boolean exec(Runtime rt, Env env) {
            Object v = init.eval(rt, env);
            if (env.vars.containsKey(name)) {
                throw err(nameLine, nameCol, "'" + name + "' already declared");
            }
            env.vars.put(name, v);
            return false;
        }
    }

    private static final class FnDecl extends Stmt {
        final String name;
        final int nameLine;
        final int nameCol;
        final List<String> params;
        final List<Stmt> body;

        FnDecl(String name, int nl, int nc, List<String> params, List<Stmt> body, int l, int c) {
            super(l, c);
            this.name = name;
            this.nameLine = nl;
            this.nameCol = nc;
            this.params = params;
            this.body = body;
        }

        @Override
        boolean exec(Runtime rt, Env env) {
            if (env.vars.containsKey(name)) {
                throw err(nameLine, nameCol, "'" + name + "' already declared");
            }
            env.vars.put(name, new Closure(name, params, body, env));
            return false;
        }
    }

    private static final class IfStmt extends Stmt {
        final Expr cond;
        final List<Stmt> then;
        final List<Stmt> otherwise; // may be null

        IfStmt(Expr cond, List<Stmt> then, List<Stmt> otherwise, int l, int c) {
            super(l, c);
            this.cond = cond;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        boolean exec(Runtime rt, Env env) {
            Object v = cond.eval(rt, env);
            if (!(v instanceof Boolean b)) {
                throw err(line, col, "condition must be a boolean");
            }
            if (b) {
                return runBlock(rt, env, then);
            }
            return otherwise != null && runBlock(rt, env, otherwise);
        }
    }

    private static boolean runBlock(Runtime rt, Env env, List<Stmt> body) {
        Env scope = new Env(env);
        for (Stmt s : body) {
            if (s.exec(rt, scope)) {
                return true;
            }
        }
        return false;
    }

    private static final class WhileStmt extends Stmt {
        final Expr cond;
        final List<Stmt> body;

        WhileStmt(Expr cond, List<Stmt> body, int l, int c) {
            super(l, c);
            this.cond = cond;
            this.body = body;
        }

        @Override
        boolean exec(Runtime rt, Env env) {
            while (true) {
                Object v = cond.eval(rt, env);
                if (!(v instanceof Boolean b)) {
                    throw err(line, col, "condition must be a boolean");
                }
                if (!b) {
                    return false;
                }
                if (runBlock(rt, env, body)) {
                    return true;
                }
            }
        }
    }

    private static final class ReturnStmt extends Stmt {
        final Expr value; // may be null

        ReturnStmt(Expr value, int l, int c) {
            super(l, c);
            this.value = value;
        }

        @Override
        boolean exec(Runtime rt, Env env) {
            Object v = value == null ? Nil.INSTANCE : value.eval(rt, env);
            rt.returnValue = v;
            return true;
        }
    }

    private static final class PrintStmt extends Stmt {
        final Expr value;

        PrintStmt(Expr value, int l, int c) {
            super(l, c);
            this.value = value;
        }

        @Override
        boolean exec(Runtime rt, Env env) {
            rt.out.add(show(value.eval(rt, env)));
            return false;
        }
    }

    private static final class AssignStmt extends Stmt {
        final String name;
        final Expr value;

        AssignStmt(String name, Expr value, int l, int c) {
            super(l, c);
            this.name = name;
            this.value = value;
        }

        @Override
        boolean exec(Runtime rt, Env env) {
            Object v = value.eval(rt, env);
            if (!env.assign(name, v)) {
                throw err(line, col, "undefined variable '" + name + "'");
            }
            return false;
        }
    }

    private static final class ExprStmt extends Stmt {
        final Expr expr;

        ExprStmt(Expr expr, int l, int c) {
            super(l, c);
            this.expr = expr;
        }

        @Override
        boolean exec(Runtime rt, Env env) {
            expr.eval(rt, env);
            return false;
        }
    }

    // ---------------------------------------------------------------- parser

    private static final class Parser {
        private final List<Token> toks;
        private int p;
        private int fnDepth;

        Parser(List<Token> toks) {
            this.toks = toks;
        }

        private Token cur() {
            return toks.get(p);
        }

        private Token peek(int k) {
            return toks.get(Math.min(p + k, toks.size() - 1));
        }

        private boolean at(T t) {
            return cur().type() == t;
        }

        private Token next() {
            Token t = cur();
            if (t.type() != T.EOF && t.type() != T.ERROR) {
                p++;
            }
            return t;
        }

        private ScriptException fail(Token t, String msg) {
            String m = t.type() == T.ERROR ? t.text() : msg;
            return err(t.line(), t.col(), m);
        }

        private Token expect(T t, String what) {
            if (!at(t)) {
                throw fail(cur(), "expected " + what);
            }
            return next();
        }

        List<Stmt> parseProgram() {
            List<Stmt> out = new ArrayList<>();
            while (!at(T.EOF)) {
                out.add(statement());
            }
            return out;
        }

        private List<Stmt> block() {
            expect(T.LBRACE, "'{'");
            List<Stmt> out = new ArrayList<>();
            while (!at(T.RBRACE)) {
                if (at(T.EOF) || at(T.ERROR)) {
                    throw fail(cur(), "expected '}'");
                }
                out.add(statement());
            }
            next();
            return out;
        }

        private List<String> params() {
            List<String> ps = new ArrayList<>();
            if (!at(T.RPAREN)) {
                do {
                    Token id = expect(T.IDENT, "parameter name");
                    if (ps.contains(id.text())) {
                        throw fail(id, "duplicate parameter '" + id.text() + "'");
                    }
                    ps.add(id.text());
                } while (at(T.COMMA) && next() != null);
            }
            return ps;
        }

        private List<Stmt> functionBody() {
            fnDepth++;
            try {
                return block();
            } finally {
                fnDepth--;
            }
        }

        private Stmt statement() {
            Token t = cur();
            switch (t.type()) {
                case LET: {
                    next();
                    Token id = expect(T.IDENT, "identifier");
                    expect(T.ASSIGN, "'='");
                    Expr e = expr();
                    expect(T.SEMI, "';'");
                    return new LetStmt(id.text(), id.line(), id.col(), e, t.line(), t.col());
                }
                case FN:
                    if (peek(1).type() == T.IDENT) {
                        next();
                        Token id = next();
                        expect(T.LPAREN, "'('");
                        List<String> ps = params();
                        expect(T.RPAREN, "')'");
                        List<Stmt> body = functionBody();
                        return new FnDecl(id.text(), id.line(), id.col(), ps, body, t.line(), t.col());
                    }
                    break;
                case IF:
                    return ifStatement();
                case WHILE: {
                    next();
                    expect(T.LPAREN, "'('");
                    Expr c = expr();
                    expect(T.RPAREN, "')'");
                    List<Stmt> body = block();
                    return new WhileStmt(c, body, t.line(), t.col());
                }
                case RETURN: {
                    if (fnDepth == 0) {
                        throw fail(t, "'return' outside function");
                    }
                    next();
                    Expr e = null;
                    if (!at(T.SEMI)) {
                        e = expr();
                    }
                    expect(T.SEMI, "';'");
                    return new ReturnStmt(e, t.line(), t.col());
                }
                case PRINT: {
                    next();
                    Expr e = expr();
                    expect(T.SEMI, "';'");
                    return new PrintStmt(e, t.line(), t.col());
                }
                case IDENT:
                    if (peek(1).type() == T.ASSIGN) {
                        next();
                        next();
                        Expr e = expr();
                        expect(T.SEMI, "';'");
                        return new AssignStmt(t.text(), e, t.line(), t.col());
                    }
                    break;
                default:
                    break;
            }
            Expr e = expr();
            expect(T.SEMI, "';'");
            return new ExprStmt(e, t.line(), t.col());
        }

        private Stmt ifStatement() {
            Token t = expect(T.IF, "'if'");
            expect(T.LPAREN, "'('");
            Expr c = expr();
            expect(T.RPAREN, "')'");
            List<Stmt> then = block();
            List<Stmt> other = null;
            if (at(T.ELSE)) {
                next();
                if (at(T.IF)) {
                    other = new ArrayList<>();
                    other.add(ifStatement());
                } else {
                    other = block();
                }
            }
            return new IfStmt(c, then, other, t.line(), t.col());
        }

        private Expr expr() {
            Expr l = and();
            while (at(T.OR)) {
                Token op = next();
                l = new Binary(T.OR, l, and(), op.line(), op.col());
            }
            return l;
        }

        private Expr and() {
            Expr l = equality();
            while (at(T.AND)) {
                Token op = next();
                l = new Binary(T.AND, l, equality(), op.line(), op.col());
            }
            return l;
        }

        private Expr equality() {
            Expr l = comparison();
            while (at(T.EQ) || at(T.NE)) {
                Token op = next();
                l = new Binary(op.type(), l, comparison(), op.line(), op.col());
            }
            return l;
        }

        private Expr comparison() {
            Expr l = additive();
            while (at(T.LT) || at(T.LE) || at(T.GT) || at(T.GE)) {
                Token op = next();
                l = new Binary(op.type(), l, additive(), op.line(), op.col());
            }
            return l;
        }

        private Expr additive() {
            Expr l = term();
            while (at(T.PLUS) || at(T.MINUS)) {
                Token op = next();
                l = new Binary(op.type(), l, term(), op.line(), op.col());
            }
            return l;
        }

        private Expr term() {
            Expr l = unary();
            while (at(T.STAR) || at(T.SLASH) || at(T.PERCENT)) {
                Token op = next();
                l = new Binary(op.type(), l, unary(), op.line(), op.col());
            }
            return l;
        }

        private Expr unary() {
            if (at(T.MINUS) || at(T.BANG)) {
                Token op = next();
                return new Unary(op.type(), unary(), op.line(), op.col());
            }
            return call();
        }

        private Expr call() {
            Expr e = primary();
            while (at(T.LPAREN)) {
                Token lp = next();
                List<Expr> args = new ArrayList<>();
                if (!at(T.RPAREN)) {
                    args.add(expr());
                    while (at(T.COMMA)) {
                        next();
                        args.add(expr());
                    }
                }
                expect(T.RPAREN, "')'");
                e = new Call(e, args, lp.line(), lp.col());
            }
            return e;
        }

        private Expr primary() {
            Token t = cur();
            switch (t.type()) {
                case INT:
                    next();
                    return new Lit(t.value(), t.line(), t.col());
                case TRUE:
                    next();
                    return new Lit(Boolean.TRUE, t.line(), t.col());
                case FALSE:
                    next();
                    return new Lit(Boolean.FALSE, t.line(), t.col());
                case NIL:
                    next();
                    return new Lit(Nil.INSTANCE, t.line(), t.col());
                case IDENT:
                    next();
                    return new Var(t.text(), t.line(), t.col());
                case LPAREN: {
                    next();
                    Expr e = expr();
                    expect(T.RPAREN, "')'");
                    return e;
                }
                case FN: {
                    next();
                    expect(T.LPAREN, "'('");
                    List<String> ps = params();
                    expect(T.RPAREN, "')'");
                    List<Stmt> body = functionBody();
                    return new FnExpr(ps, body, t.line(), t.col());
                }
                default:
                    throw fail(t, "unexpected token");
            }
        }
    }
}
