package bench.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tree-walking interpreter for a small imperative language with closures. */
public final class Interpreter {
    private static final int MAX_CALL_DEPTH = 50_000;
    private static final long STACK_BYTES = 1L << 30;

    private Interpreter() {
    }

    /** Parses and runs {@code source}, returning the printed lines. */
    public static List<String> run(String source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        final Object[] box = new Object[1];
        Thread t = new Thread(null, () -> {
            try {
                box[0] = runInThread(source);
            } catch (Throwable e) {
                box[0] = e;
            }
        }, "script-interpreter", STACK_BYTES);
        t.start();
        boolean interrupted = false;
        while (true) {
            try {
                t.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        Object r = box[0];
        if (r instanceof RuntimeException re) {
            throw re;
        }
        if (r instanceof Error er) {
            throw er;
        }
        if (r instanceof Throwable th) {
            throw new IllegalStateException(th);
        }
        @SuppressWarnings("unchecked")
        List<String> out = (List<String>) r;
        return out;
    }

    private static List<String> runInThread(String source) {
        List<Token> tokens = new Lexer(source).lex();
        List<Stmt> program;
        try {
            program = new Parser(tokens).parseProgram();
        } catch (StackOverflowError so) {
            throw new ScriptException(1, 1, "program nested too deeply");
        }
        Evaluator ev = new Evaluator();
        try {
            ev.runProgram(program);
        } catch (StackOverflowError so) {
            throw new ScriptException(1, 1, "call stack too deep");
        }
        return ev.output;
    }

    // ------------------------------------------------------------------ tokens

    private enum T {
        INT, IDENT, LET, FN, IF, ELSE, WHILE, RETURN, PRINT, TRUE, FALSE, NIL,
        PLUS, MINUS, STAR, SLASH, PERCENT, LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMI,
        ASSIGN, EQ, NE, LT, LE, GT, GE, AND, OR, NOT, EOF, ERROR
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

        private char cur() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private char at(int i) {
            return i < src.length() ? src.charAt(i) : '\0';
        }

        /** Advances over one source character (a code point counts as one column). */
        private void advance() {
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
                col = 1;
                pos++;
            } else if (Character.isHighSurrogate(c) && pos + 1 < src.length()
                    && Character.isLowSurrogate(src.charAt(pos + 1))) {
                pos += 2;
                col++;
            } else {
                pos++;
                col++;
            }
        }

        private static boolean letter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }

        private static boolean digit(char c) {
            return c >= '0' && c <= '9';
        }

        List<Token> lex() {
            while (true) {
                while (pos < src.length()) {
                    char c = src.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                        advance();
                    } else if (c == '/' && at(pos + 1) == '/') {
                        while (pos < src.length() && src.charAt(pos) != '\n') {
                            advance();
                        }
                    } else {
                        break;
                    }
                }
                if (pos >= src.length()) {
                    out.add(new Token(T.EOF, "", 0, line, col));
                    return out;
                }
                int sl = line;
                int sc = col;
                char c = src.charAt(pos);
                if (digit(c)) {
                    int start = pos;
                    while (digit(cur())) {
                        pos++;
                        col++;
                    }
                    String text = src.substring(start, pos);
                    long v;
                    try {
                        v = Long.parseLong(text);
                    } catch (NumberFormatException e) {
                        out.add(new Token(T.ERROR, "integer literal too large", 0, sl, sc));
                        return out;
                    }
                    out.add(new Token(T.INT, text, v, sl, sc));
                } else if (letter(c)) {
                    int start = pos;
                    while (letter(cur()) || digit(cur())) {
                        pos++;
                        col++;
                    }
                    String text = src.substring(start, pos);
                    T kw = KEYWORDS.get(text);
                    out.add(new Token(kw != null ? kw : T.IDENT, text, 0, sl, sc));
                } else {
                    T type = null;
                    int len = 1;
                    char n = at(pos + 1);
                    switch (c) {
                        case '+' -> type = T.PLUS;
                        case '-' -> type = T.MINUS;
                        case '*' -> type = T.STAR;
                        case '/' -> type = T.SLASH;
                        case '%' -> type = T.PERCENT;
                        case '(' -> type = T.LPAREN;
                        case ')' -> type = T.RPAREN;
                        case '{' -> type = T.LBRACE;
                        case '}' -> type = T.RBRACE;
                        case ',' -> type = T.COMMA;
                        case ';' -> type = T.SEMI;
                        case '=' -> {
                            if (n == '=') {
                                type = T.EQ;
                                len = 2;
                            } else {
                                type = T.ASSIGN;
                            }
                        }
                        case '!' -> {
                            if (n == '=') {
                                type = T.NE;
                                len = 2;
                            } else {
                                type = T.NOT;
                            }
                        }
                        case '<' -> {
                            if (n == '=') {
                                type = T.LE;
                                len = 2;
                            } else {
                                type = T.LT;
                            }
                        }
                        case '>' -> {
                            if (n == '=') {
                                type = T.GE;
                                len = 2;
                            } else {
                                type = T.GT;
                            }
                        }
                        case '&' -> {
                            if (n == '&') {
                                type = T.AND;
                                len = 2;
                            }
                        }
                        case '|' -> {
                            if (n == '|') {
                                type = T.OR;
                                len = 2;
                            }
                        }
                        default -> {
                        }
                    }
                    if (type == null) {
                        out.add(new Token(T.ERROR, "unexpected character", 0, sl, sc));
                        return out;
                    }
                    out.add(new Token(type, src.substring(pos, pos + len), 0, sl, sc));
                    pos += len;
                    col += len;
                }
            }
        }
    }

    // ------------------------------------------------------------------ AST

    private sealed interface Expr permits IntLit, BoolLit, NilLit, Var, FnExpr, Unary, Binary, Logic, Call {
    }

    private record IntLit(Long value) implements Expr {
    }

    private record BoolLit(Boolean value) implements Expr {
    }

    private record NilLit() implements Expr {
    }

    private record Var(String name, int line, int col) implements Expr {
    }

    private record FnExpr(String name, List<String> params, List<Stmt> body) implements Expr {
    }

    private record Unary(T op, Expr operand, int line, int col) implements Expr {
    }

    private record Binary(T op, Expr left, Expr right, int line, int col) implements Expr {
    }

    private record Logic(boolean isAnd, Expr left, Expr right, int line, int col) implements Expr {
    }

    private record Call(Expr callee, List<Expr> args, int line, int col) implements Expr {
    }

    private sealed interface Stmt permits Let, FnDecl, If, While, Return, Print, Assign, ExprStmt {
    }

    private record Let(String name, Expr init, int line, int col) implements Stmt {
    }

    private record FnDecl(String name, FnExpr fn, int line, int col) implements Stmt {
    }

    private record If(Expr cond, List<Stmt> then, List<Stmt> otherwise, int line, int col) implements Stmt {
    }

    private record While(Expr cond, List<Stmt> body, int line, int col) implements Stmt {
    }

    private record Return(Expr value) implements Stmt {
    }

    private record Print(Expr value) implements Stmt {
    }

    private record Assign(String name, Expr value, int line, int col) implements Stmt {
    }

    private record ExprStmt(Expr expr) implements Stmt {
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        private final List<Token> toks;
        private int p;
        private int fnDepth;

        Parser(List<Token> toks) {
            this.toks = toks;
        }

        private Token peek() {
            return peek(0);
        }

        private Token peek(int k) {
            int i = Math.min(p + k, toks.size() - 1);
            Token t = toks.get(i);
            if (t.type() == T.ERROR) {
                throw new ScriptException(t.line(), t.col(), t.text());
            }
            return t;
        }

        private Token next() {
            Token t = peek();
            if (t.type() != T.EOF) {
                p++;
            }
            return t;
        }

        private boolean check(T type) {
            return peek().type() == type;
        }

        private boolean match(T type) {
            if (check(type)) {
                p++;
                return true;
            }
            return false;
        }

        private ScriptException err(Token t, String msg) {
            return new ScriptException(t.line(), t.col(), msg);
        }

        private Token expect(T type, String what) {
            Token t = peek();
            if (t.type() != type) {
                throw err(t, "expected " + what);
            }
            p++;
            return t;
        }

        List<Stmt> parseProgram() {
            List<Stmt> out = new ArrayList<>();
            while (!check(T.EOF)) {
                out.add(statement());
            }
            return out;
        }

        private List<Stmt> block() {
            expect(T.LBRACE, "'{'");
            List<Stmt> out = new ArrayList<>();
            while (!check(T.RBRACE)) {
                if (check(T.EOF)) {
                    throw err(peek(), "expected '}'");
                }
                out.add(statement());
            }
            p++;
            return out;
        }

        private Stmt statement() {
            Token t = peek();
            switch (t.type()) {
                case LET: {
                    p++;
                    Token name = expect(T.IDENT, "identifier");
                    expect(T.ASSIGN, "'='");
                    Expr e = expr();
                    expect(T.SEMI, "';'");
                    return new Let(name.text(), e, name.line(), name.col());
                }
                case FN: {
                    if (peek(1).type() == T.IDENT) {
                        p++;
                        Token name = next();
                        FnExpr f = functionRest(name.text());
                        return new FnDecl(name.text(), f, name.line(), name.col());
                    }
                    if (peek(1).type() == T.LPAREN) {
                        break; // anonymous function expression statement
                    }
                    throw err(peek(1), "expected function name or '('");
                }
                case IF:
                    return ifStatement();
                case WHILE: {
                    p++;
                    expect(T.LPAREN, "'('");
                    Expr c = expr();
                    expect(T.RPAREN, "')'");
                    List<Stmt> body = block();
                    return new While(c, body, t.line(), t.col());
                }
                case RETURN: {
                    if (fnDepth == 0) {
                        throw err(t, "'return' outside of function");
                    }
                    p++;
                    Expr v = null;
                    if (!check(T.SEMI)) {
                        v = expr();
                    }
                    expect(T.SEMI, "';'");
                    return new Return(v);
                }
                case PRINT: {
                    p++;
                    Expr v = expr();
                    expect(T.SEMI, "';'");
                    return new Print(v);
                }
                case IDENT: {
                    if (peek(1).type() == T.ASSIGN) {
                        p += 2;
                        Expr v = expr();
                        expect(T.SEMI, "';'");
                        return new Assign(t.text(), v, t.line(), t.col());
                    }
                    break;
                }
                default:
                    break;
            }
            Expr e = expr();
            expect(T.SEMI, "';'");
            return new ExprStmt(e);
        }

        private Stmt ifStatement() {
            Token t = expect(T.IF, "'if'");
            expect(T.LPAREN, "'('");
            Expr c = expr();
            expect(T.RPAREN, "')'");
            List<Stmt> then = block();
            List<Stmt> otherwise = null;
            if (match(T.ELSE)) {
                if (check(T.IF)) {
                    otherwise = List.of(ifStatement());
                } else {
                    otherwise = block();
                }
            }
            return new If(c, then, otherwise, t.line(), t.col());
        }

        /** Parses {@code "(" [params] ")" block} for a function. */
        private FnExpr functionRest(String name) {
            expect(T.LPAREN, "'('");
            List<String> params = new ArrayList<>();
            if (!check(T.RPAREN)) {
                do {
                    Token id = expect(T.IDENT, "parameter name");
                    if (params.contains(id.text())) {
                        throw err(id, "duplicate parameter '" + id.text() + "'");
                    }
                    params.add(id.text());
                } while (match(T.COMMA));
            }
            expect(T.RPAREN, "')'");
            fnDepth++;
            List<Stmt> body;
            try {
                body = block();
            } finally {
                fnDepth--;
            }
            return new FnExpr(name, params, body);
        }

        private Expr expr() {
            Expr left = and();
            while (check(T.OR)) {
                Token op = next();
                Expr right = and();
                left = new Logic(false, left, right, op.line(), op.col());
            }
            return left;
        }

        private Expr and() {
            Expr left = equality();
            while (check(T.AND)) {
                Token op = next();
                Expr right = equality();
                left = new Logic(true, left, right, op.line(), op.col());
            }
            return left;
        }

        private Expr equality() {
            Expr left = comparison();
            while (check(T.EQ) || check(T.NE)) {
                Token op = next();
                Expr right = comparison();
                left = new Binary(op.type(), left, right, op.line(), op.col());
            }
            return left;
        }

        private Expr comparison() {
            Expr left = additive();
            while (check(T.LT) || check(T.LE) || check(T.GT) || check(T.GE)) {
                Token op = next();
                Expr right = additive();
                left = new Binary(op.type(), left, right, op.line(), op.col());
            }
            return left;
        }

        private Expr additive() {
            Expr left = term();
            while (check(T.PLUS) || check(T.MINUS)) {
                Token op = next();
                Expr right = term();
                left = new Binary(op.type(), left, right, op.line(), op.col());
            }
            return left;
        }

        private Expr term() {
            Expr left = unary();
            while (check(T.STAR) || check(T.SLASH) || check(T.PERCENT)) {
                Token op = next();
                Expr right = unary();
                left = new Binary(op.type(), left, right, op.line(), op.col());
            }
            return left;
        }

        private Expr unary() {
            if (check(T.MINUS) || check(T.NOT)) {
                Token op = next();
                Expr operand = unary();
                return new Unary(op.type(), operand, op.line(), op.col());
            }
            return call();
        }

        private Expr call() {
            Expr e = primary();
            while (check(T.LPAREN)) {
                Token paren = next();
                List<Expr> args = new ArrayList<>();
                if (!check(T.RPAREN)) {
                    do {
                        args.add(expr());
                    } while (match(T.COMMA));
                }
                expect(T.RPAREN, "')'");
                e = new Call(e, args, paren.line(), paren.col());
            }
            return e;
        }

        private Expr primary() {
            Token t = peek();
            switch (t.type()) {
                case INT:
                    p++;
                    return new IntLit(t.value());
                case TRUE:
                    p++;
                    return new BoolLit(Boolean.TRUE);
                case FALSE:
                    p++;
                    return new BoolLit(Boolean.FALSE);
                case NIL:
                    p++;
                    return new NilLit();
                case IDENT:
                    p++;
                    return new Var(t.text(), t.line(), t.col());
                case LPAREN: {
                    p++;
                    Expr e = expr();
                    expect(T.RPAREN, "')'");
                    return e;
                }
                case FN: {
                    p++;
                    return functionRest(null);
                }
                default:
                    throw err(t, "unexpected token");
            }
        }
    }

    // ------------------------------------------------------------------ runtime

    private static final class Nil {
        @Override
        public String toString() {
            return "nil";
        }
    }

    private static final Nil NIL = new Nil();

    private static final class Env {
        final Env parent;
        Map<String, Object> vars;

        Env(Env parent) {
            this.parent = parent;
        }

        boolean declaredHere(String name) {
            return vars != null && vars.containsKey(name);
        }

        void declare(String name, Object v) {
            if (vars == null) {
                vars = new HashMap<>(8);
            }
            vars.put(name, v);
        }
    }

    private static final class Fn {
        final FnExpr def;
        final Env closure;

        Fn(FnExpr def, Env closure) {
            this.def = def;
            this.closure = closure;
        }

        @Override
        public String toString() {
            return def.name() == null ? "<fn>" : "<fn " + def.name() + ">";
        }
    }

    /** Signals a {@code return} out of a statement list. */
    private static final class ReturnSignal {
        final Object value;

        ReturnSignal(Object value) {
            this.value = value;
        }
    }

    private static final class Evaluator {
        final List<String> output = new ArrayList<>();
        private int depth;

        void runProgram(List<Stmt> program) {
            Env global = new Env(null);
            for (Stmt s : program) {
                exec(s, global);
            }
        }

        private Object execBlock(List<Stmt> body, Env env) {
            for (int i = 0, n = body.size(); i < n; i++) {
                Object r = exec(body.get(i), env);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }

        /** Returns null normally, or a ReturnSignal. */
        private Object exec(Stmt s, Env env) {
            switch (s) {
                case ExprStmt es -> {
                    eval(es.expr(), env);
                    return null;
                }
                case Let l -> {
                    Object v = eval(l.init(), env);
                    if (env.declaredHere(l.name())) {
                        throw new ScriptException(l.line(), l.col(), "'" + l.name() + "' already declared");
                    }
                    env.declare(l.name(), v);
                    return null;
                }
                case FnDecl d -> {
                    if (env.declaredHere(d.name())) {
                        throw new ScriptException(d.line(), d.col(), "'" + d.name() + "' already declared");
                    }
                    env.declare(d.name(), new Fn(d.fn(), env));
                    return null;
                }
                case Assign a -> {
                    Object v = eval(a.value(), env);
                    for (Env e = env; e != null; e = e.parent) {
                        if (e.vars != null && e.vars.containsKey(a.name())) {
                            e.vars.put(a.name(), v);
                            return null;
                        }
                    }
                    throw new ScriptException(a.line(), a.col(), "undeclared variable '" + a.name() + "'");
                }
                case Print p -> {
                    output.add(show(eval(p.value(), env)));
                    return null;
                }
                case Return r -> {
                    return new ReturnSignal(r.value() == null ? NIL : eval(r.value(), env));
                }
                case If i -> {
                    Object c = eval(i.cond(), env);
                    if (!(c instanceof Boolean b)) {
                        throw new ScriptException(i.line(), i.col(), "condition must be a boolean");
                    }
                    if (b) {
                        return execBlock(i.then(), new Env(env));
                    } else if (i.otherwise() != null) {
                        return execBlock(i.otherwise(), new Env(env));
                    }
                    return null;
                }
                case While w -> {
                    while (true) {
                        Object c = eval(w.cond(), env);
                        if (!(c instanceof Boolean b)) {
                            throw new ScriptException(w.line(), w.col(), "condition must be a boolean");
                        }
                        if (!b) {
                            return null;
                        }
                        Object r = execBlock(w.body(), new Env(env));
                        if (r != null) {
                            return r;
                        }
                    }
                }
            }
        }

        private static String show(Object v) {
            return v.toString();
        }

        private Object eval(Expr e, Env env) {
            switch (e) {
                case IntLit i -> {
                    return i.value();
                }
                case BoolLit b -> {
                    return b.value();
                }
                case NilLit n -> {
                    return NIL;
                }
                case Var v -> {
                    for (Env x = env; x != null; x = x.parent) {
                        if (x.vars != null) {
                            Object o = x.vars.get(v.name());
                            if (o != null) {
                                return o;
                            }
                        }
                    }
                    throw new ScriptException(v.line(), v.col(), "undeclared variable '" + v.name() + "'");
                }
                case FnExpr f -> {
                    return new Fn(f, env);
                }
                case Unary u -> {
                    Object o = eval(u.operand(), env);
                    if (u.op() == T.MINUS) {
                        if (o instanceof Long l) {
                            return -l;
                        }
                        throw new ScriptException(u.line(), u.col(), "operand must be an integer");
                    }
                    if (o instanceof Boolean b) {
                        return !b;
                    }
                    throw new ScriptException(u.line(), u.col(), "operand must be a boolean");
                }
                case Logic lg -> {
                    Object l = eval(lg.left(), env);
                    if (!(l instanceof Boolean lb)) {
                        throw new ScriptException(lg.line(), lg.col(), "operand must be a boolean");
                    }
                    if (lg.isAnd() ? !lb : lb) {
                        return lb;
                    }
                    Object r = eval(lg.right(), env);
                    if (!(r instanceof Boolean)) {
                        throw new ScriptException(lg.line(), lg.col(), "operand must be a boolean");
                    }
                    return r;
                }
                case Binary b -> {
                    return binary(b, eval(b.left(), env), eval(b.right(), env));
                }
                case Call c -> {
                    return call(c, env);
                }
            }
        }

        private Object binary(Binary b, Object l, Object r) {
            T op = b.op();
            if (op == T.EQ) {
                return equal(l, r);
            }
            if (op == T.NE) {
                return !equal(l, r);
            }
            if (!(l instanceof Long x) || !(r instanceof Long y)) {
                throw new ScriptException(b.line(), b.col(), "operands must be integers");
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
                        throw new ScriptException(b.line(), b.col(), "division by zero");
                    }
                    return x / y;
                case PERCENT:
                    if (y == 0) {
                        throw new ScriptException(b.line(), b.col(), "division by zero");
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

        private static boolean equal(Object l, Object r) {
            if (l instanceof Long a && r instanceof Long b) {
                return a.longValue() == b.longValue();
            }
            if (l instanceof Boolean a && r instanceof Boolean b) {
                return a.booleanValue() == b.booleanValue();
            }
            return l == r; // nil singleton, function identity
        }

        private Object call(Call c, Env env) {
            Object callee = eval(c.callee(), env);
            int n = c.args().size();
            Object[] args = new Object[n];
            for (int i = 0; i < n; i++) {
                args[i] = eval(c.args().get(i), env);
            }
            if (!(callee instanceof Fn fn)) {
                throw new ScriptException(c.line(), c.col(), "not a function");
            }
            List<String> params = fn.def.params();
            if (params.size() != n) {
                throw new ScriptException(c.line(), c.col(),
                        "expected " + params.size() + " arguments but got " + n);
            }
            if (depth >= MAX_CALL_DEPTH) {
                throw new ScriptException(c.line(), c.col(), "call stack too deep");
            }
            Env callEnv = new Env(fn.closure);
            for (int i = 0; i < n; i++) {
                callEnv.declare(params.get(i), args[i]);
            }
            depth++;
            try {
                Object r = execBlock(fn.def.body(), callEnv);
                return r == null ? NIL : ((ReturnSignal) r).value;
            } catch (StackOverflowError so) {
                throw new ScriptException(c.line(), c.col(), "call stack too deep");
            } finally {
                depth--;
            }
        }
    }
}
