package bench.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Interpreter for a small scripting language: lazy lexer, recursive-descent parser, tree walker. */
public final class Interpreter {
    private static final int MAX_DEPTH = 20_000;
    private static final long STACK_BYTES = 768L << 20;

    private Interpreter() { }

    /** Parses and runs {@code source}; returns the lines printed. */
    public static List<String> run(String source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        Object[] result = new Object[1];
        Throwable[] error = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                result[0] = runInternal(source);
            } catch (Throwable e) {
                error[0] = e;
            }
        }, "script-runner", STACK_BYTES);
        t.setDaemon(true);
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
        if (error[0] instanceof RuntimeException re) {
            throw re;
        }
        if (error[0] instanceof Error er) {
            throw er;
        }
        if (error[0] != null) {
            throw new IllegalStateException(error[0]);
        }
        @SuppressWarnings("unchecked")
        List<String> out = (List<String>) result[0];
        return out;
    }

    private static List<String> runInternal(String source) {
        List<Stmt> program;
        try {
            program = new Parser(new Lexer(source)).parseProgram();
        } catch (StackOverflowError e) {
            throw new ScriptException(1, 1, "program nested too deeply");
        }
        Machine m = new Machine();
        try {
            m.execList(program, new Env(null));
        } catch (StackOverflowError e) {
            throw new ScriptException(m.lastLine, m.lastCol, "stack overflow");
        }
        return m.out;
    }

    // ---------------------------------------------------------------- lexer

    private record Token(String kind, String text, long value, int line, int col) { }

    private static final Set<String> KEYWORDS =
        Set.of("let", "fn", "if", "else", "while", "return", "print", "true", "false", "nil");

    private static final String INT = "<int>";
    private static final String ID = "<id>";
    private static final String EOF = "<eof>";

    private static final class Lexer {
        private final String s;
        private int pos;
        private int line = 1;
        private int col = 1;

        Lexer(String s) {
            this.s = s;
        }

        private void advance() {
            if (s.charAt(pos) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            pos++;
        }

        Token next() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    advance();
                } else if (c == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '/') {
                    while (pos < s.length() && s.charAt(pos) != '\n') {
                        advance();
                    }
                } else {
                    break;
                }
            }
            int l = line;
            int cc = col;
            if (pos >= s.length()) {
                return new Token(EOF, "", 0, l, cc);
            }
            char c = s.charAt(pos);
            if (c >= '0' && c <= '9') {
                int start = pos;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    advance();
                }
                String text = s.substring(start, pos);
                long v;
                try {
                    v = Long.parseLong(text);
                } catch (NumberFormatException e) {
                    throw new ScriptException(l, cc, "integer literal out of range");
                }
                return new Token(INT, text, v, l, cc);
            }
            if (isLetter(c) || c == '_') {
                int start = pos;
                while (pos < s.length() && (isLetter(s.charAt(pos)) || s.charAt(pos) == '_'
                        || (s.charAt(pos) >= '0' && s.charAt(pos) <= '9'))) {
                    advance();
                }
                String text = s.substring(start, pos);
                return new Token(KEYWORDS.contains(text) ? text : ID, text, 0, l, cc);
            }
            char n = pos + 1 < s.length() ? s.charAt(pos + 1) : '\0';
            switch (c) {
                case '+', '-', '*', '/', '%', '(', ')', '{', '}', ',', ';' -> {
                    advance();
                    return new Token(String.valueOf(c), String.valueOf(c), 0, l, cc);
                }
                case '=', '!', '<', '>' -> {
                    advance();
                    if (n == '=') {
                        advance();
                        String op = c + "=";
                        return new Token(op, op, 0, l, cc);
                    }
                    return new Token(String.valueOf(c), String.valueOf(c), 0, l, cc);
                }
                case '&', '|' -> {
                    if (n == c) {
                        advance();
                        advance();
                        String op = "" + c + c;
                        return new Token(op, op, 0, l, cc);
                    }
                    throw new ScriptException(l, cc, "unexpected character '" + c + "'");
                }
                default -> throw new ScriptException(l, cc, "unexpected character '" + c + "'");
            }
        }

        private static boolean isLetter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }
    }

    // ------------------------------------------------------------------ AST

    private sealed interface Expr permits Lit, Var, Unary, Binary, Logical, Call, FnExpr { }

    private record Lit(Object value) implements Expr { }

    private record Var(String name, int line, int col) implements Expr { }

    private record Unary(char op, Expr operand, int line, int col) implements Expr { }

    private record Binary(String op, Expr left, Expr right, int line, int col) implements Expr { }

    private record Logical(boolean and, Expr left, Expr right, int line, int col) implements Expr { }

    private record Call(Expr callee, List<Expr> args, int line, int col) implements Expr { }

    private record FuncDef(String name, List<String> params, List<Stmt> body) { }

    private record FnExpr(FuncDef def) implements Expr { }

    private sealed interface Stmt permits Let, FnDecl, If, While, Return, Print, Assign, ExprStmt { }

    private record Let(String name, Expr init, int line, int col) implements Stmt { }

    private record FnDecl(FuncDef def, int line, int col) implements Stmt { }

    private record If(Expr cond, List<Stmt> then, List<Stmt> otherwise, int line, int col) implements Stmt { }

    private record While(Expr cond, List<Stmt> body, int line, int col) implements Stmt { }

    private record Return(Expr value) implements Stmt { }

    private record Print(Expr value) implements Stmt { }

    private record Assign(String name, Expr value, int line, int col) implements Stmt { }

    private record ExprStmt(Expr expr) implements Stmt { }

    // --------------------------------------------------------------- parser

    private static final class Parser {
        private final Lexer lex;
        private final List<Token> buf = new ArrayList<>();
        private int funcDepth;

        Parser(Lexer lex) {
            this.lex = lex;
        }

        private Token peek(int n) {
            while (buf.size() <= n) {
                buf.add(lex.next());
            }
            return buf.get(n);
        }

        private Token peek() {
            return peek(0);
        }

        private Token take() {
            Token t = peek(0);
            buf.remove(0);
            return t;
        }

        private boolean at(String kind) {
            return peek().kind().equals(kind);
        }

        private ScriptException err(Token t, String msg) {
            return new ScriptException(t.line(), t.col(), msg);
        }

        private Token expect(String kind) {
            Token t = peek();
            if (!t.kind().equals(kind)) {
                throw err(t, "expected '" + kind + "' but found '" + t.text() + "'");
            }
            return take();
        }

        List<Stmt> parseProgram() {
            List<Stmt> out = new ArrayList<>();
            while (!at(EOF)) {
                out.add(statement());
            }
            return out;
        }

        private List<Stmt> block() {
            expect("{");
            List<Stmt> out = new ArrayList<>();
            while (!at("}")) {
                if (at(EOF)) {
                    throw err(peek(), "unexpected end of input");
                }
                out.add(statement());
            }
            take();
            return out;
        }

        private Stmt statement() {
            Token t = peek();
            switch (t.kind()) {
                case "let": {
                    take();
                    Token name = expect(ID);
                    expect("=");
                    Expr e = expr();
                    expect(";");
                    return new Let(name.text(), e, name.line(), name.col());
                }
                case "fn": {
                    if (peek(1).kind().equals(ID)) {
                        take();
                        Token name = take();
                        FuncDef def = function(name.text());
                        return new FnDecl(def, name.line(), name.col());
                    }
                    break;
                }
                case "if":
                    return ifStatement();
                case "while": {
                    take();
                    expect("(");
                    Expr c = expr();
                    expect(")");
                    List<Stmt> body = block();
                    return new While(c, body, t.line(), t.col());
                }
                case "return": {
                    if (funcDepth == 0) {
                        throw err(t, "'return' outside function");
                    }
                    take();
                    Expr v = null;
                    if (!at(";")) {
                        v = expr();
                    }
                    expect(";");
                    return new Return(v);
                }
                case "print": {
                    take();
                    Expr e = expr();
                    expect(";");
                    return new Print(e);
                }
                case ID: {
                    if (peek(1).kind().equals("=")) {
                        take();
                        take();
                        Expr e = expr();
                        expect(";");
                        return new Assign(t.text(), e, t.line(), t.col());
                    }
                    break;
                }
                default:
                    break;
            }
            Expr e = expr();
            expect(";");
            return new ExprStmt(e);
        }

        private Stmt ifStatement() {
            Token t = expect("if");
            expect("(");
            Expr c = expr();
            expect(")");
            List<Stmt> then = block();
            List<Stmt> otherwise = null;
            if (at("else")) {
                take();
                if (at("if")) {
                    otherwise = List.of(ifStatement());
                } else {
                    otherwise = block();
                }
            }
            return new If(c, then, otherwise, t.line(), t.col());
        }

        /** Parses "( params ) block" after the fn keyword (and name, if any). */
        private FuncDef function(String name) {
            expect("(");
            List<String> params = new ArrayList<>();
            if (!at(")")) {
                while (true) {
                    Token p = expect(ID);
                    if (params.contains(p.text())) {
                        throw err(p, "duplicate parameter '" + p.text() + "'");
                    }
                    params.add(p.text());
                    if (at(",")) {
                        take();
                    } else {
                        break;
                    }
                }
            }
            expect(")");
            funcDepth++;
            List<Stmt> body;
            try {
                body = block();
            } finally {
                funcDepth--;
            }
            return new FuncDef(name, params, body);
        }

        private Expr expr() {
            Expr left = and();
            while (at("||")) {
                Token op = take();
                left = new Logical(false, left, and(), op.line(), op.col());
            }
            return left;
        }

        private Expr and() {
            Expr left = equality();
            while (at("&&")) {
                Token op = take();
                left = new Logical(true, left, equality(), op.line(), op.col());
            }
            return left;
        }

        private Expr equality() {
            Expr left = comparison();
            while (at("==") || at("!=")) {
                Token op = take();
                left = new Binary(op.kind(), left, comparison(), op.line(), op.col());
            }
            return left;
        }

        private Expr comparison() {
            Expr left = additive();
            while (at("<") || at("<=") || at(">") || at(">=")) {
                Token op = take();
                left = new Binary(op.kind(), left, additive(), op.line(), op.col());
            }
            return left;
        }

        private Expr additive() {
            Expr left = term();
            while (at("+") || at("-")) {
                Token op = take();
                left = new Binary(op.kind(), left, term(), op.line(), op.col());
            }
            return left;
        }

        private Expr term() {
            Expr left = unary();
            while (at("*") || at("/") || at("%")) {
                Token op = take();
                left = new Binary(op.kind(), left, unary(), op.line(), op.col());
            }
            return left;
        }

        private Expr unary() {
            if (at("-") || at("!")) {
                Token op = take();
                return new Unary(op.kind().charAt(0), unary(), op.line(), op.col());
            }
            return call();
        }

        private Expr call() {
            Expr e = primary();
            while (at("(")) {
                Token open = take();
                List<Expr> args = new ArrayList<>();
                if (!at(")")) {
                    args.add(expr());
                    while (at(",")) {
                        take();
                        args.add(expr());
                    }
                }
                expect(")");
                e = new Call(e, args, open.line(), open.col());
            }
            return e;
        }

        private Expr primary() {
            Token t = peek();
            switch (t.kind()) {
                case INT:
                    take();
                    return new Lit(t.value());
                case "true":
                    take();
                    return new Lit(Boolean.TRUE);
                case "false":
                    take();
                    return new Lit(Boolean.FALSE);
                case "nil":
                    take();
                    return new Lit(NIL);
                case ID:
                    take();
                    return new Var(t.text(), t.line(), t.col());
                case "(": {
                    take();
                    Expr e = expr();
                    expect(")");
                    return e;
                }
                case "fn": {
                    take();
                    return new FnExpr(function(null));
                }
                default:
                    throw err(t, "unexpected '" + (t.kind().equals(EOF) ? "end of input" : t.text()) + "'");
            }
        }
    }

    // --------------------------------------------------------------- values

    private static final class Nil {
        @Override
        public String toString() {
            return "nil";
        }
    }

    private static final Object NIL = new Nil();

    private static final class Fn {
        final FuncDef def;
        final Env closure;

        Fn(FuncDef def, Env closure) {
            this.def = def;
            this.closure = closure;
        }

        @Override
        public String toString() {
            return def.name() == null ? "<fn>" : "<fn " + def.name() + ">";
        }
    }

    private static final class Env {
        final Env parent;
        HashMap<String, Object> vars;

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

        Object lookup(String name) {
            for (Env e = this; e != null; e = e.parent) {
                if (e.vars != null) {
                    Object v = e.vars.get(name);
                    if (v != null) {
                        return v;
                    }
                }
            }
            return null;
        }

        boolean assign(String name, Object v) {
            for (Env e = this; e != null; e = e.parent) {
                if (e.vars != null && e.vars.containsKey(name)) {
                    e.vars.put(name, v);
                    return true;
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------- evaluator

    private static final class Machine {
        final List<String> out = new ArrayList<>();
        Object retVal = NIL;
        int depth;
        int lastLine = 1;
        int lastCol = 1;

        /** Runs statements; returns true if a return statement executed. */
        boolean execList(List<Stmt> stmts, Env env) {
            for (int i = 0, n = stmts.size(); i < n; i++) {
                if (exec(stmts.get(i), env)) {
                    return true;
                }
            }
            return false;
        }

        boolean exec(Stmt s, Env env) {
            switch (s) {
                case Let l -> {
                    Object v = eval(l.init(), env);
                    if (env.declaredHere(l.name())) {
                        throw new ScriptException(l.line(), l.col(), "'" + l.name() + "' already declared");
                    }
                    env.declare(l.name(), v);
                }
                case FnDecl d -> {
                    if (env.declaredHere(d.def().name())) {
                        throw new ScriptException(d.line(), d.col(), "'" + d.def().name() + "' already declared");
                    }
                    env.declare(d.def().name(), new Fn(d.def(), env));
                }
                case If i -> {
                    Object c = eval(i.cond(), env);
                    if (!(c instanceof Boolean b)) {
                        throw new ScriptException(i.line(), i.col(), "condition must be a boolean");
                    }
                    if (b) {
                        return execList(i.then(), new Env(env));
                    } else if (i.otherwise() != null) {
                        return execList(i.otherwise(), new Env(env));
                    }
                }
                case While w -> {
                    while (true) {
                        Object c = eval(w.cond(), env);
                        if (!(c instanceof Boolean b)) {
                            throw new ScriptException(w.line(), w.col(), "condition must be a boolean");
                        }
                        if (!b) {
                            break;
                        }
                        if (execList(w.body(), new Env(env))) {
                            return true;
                        }
                    }
                }
                case Return r -> {
                    retVal = r.value() == null ? NIL : eval(r.value(), env);
                    return true;
                }
                case Print p -> out.add(String.valueOf(eval(p.value(), env)));
                case Assign a -> {
                    Object v = eval(a.value(), env);
                    if (!env.assign(a.name(), v)) {
                        throw new ScriptException(a.line(), a.col(), "undeclared variable '" + a.name() + "'");
                    }
                }
                case ExprStmt e -> eval(e.expr(), env);
            }
            return false;
        }

        Object eval(Expr e, Env env) {
            switch (e) {
                case Lit l -> {
                    return l.value();
                }
                case Var v -> {
                    Object x = env.lookup(v.name());
                    if (x == null) {
                        throw new ScriptException(v.line(), v.col(), "undeclared variable '" + v.name() + "'");
                    }
                    return x;
                }
                case FnExpr f -> {
                    return new Fn(f.def(), env);
                }
                case Unary u -> {
                    Object x = eval(u.operand(), env);
                    if (u.op() == '-') {
                        if (x instanceof Long n) {
                            return -n;
                        }
                        throw new ScriptException(u.line(), u.col(), "operand must be an integer");
                    }
                    if (x instanceof Boolean b) {
                        return !b;
                    }
                    throw new ScriptException(u.line(), u.col(), "operand must be a boolean");
                }
                case Logical lg -> {
                    Object a = eval(lg.left(), env);
                    if (!(a instanceof Boolean ab)) {
                        throw new ScriptException(lg.line(), lg.col(), "operand must be a boolean");
                    }
                    if (lg.and() != ab) {
                        // && with false, or || with true: short-circuit
                        return ab;
                    }
                    Object b = eval(lg.right(), env);
                    if (!(b instanceof Boolean)) {
                        throw new ScriptException(lg.line(), lg.col(), "operand must be a boolean");
                    }
                    return b;
                }
                case Binary b -> {
                    return binary(b, eval(b.left(), env), eval(b.right(), env));
                }
                case Call c -> {
                    return call(c, env);
                }
            }
        }

        private Object binary(Binary b, Object x, Object y) {
            String op = b.op();
            if (op.equals("==")) {
                return valuesEqual(x, y);
            }
            if (op.equals("!=")) {
                return !valuesEqual(x, y);
            }
            if (!(x instanceof Long) || !(y instanceof Long)) {
                throw new ScriptException(b.line(), b.col(), "operands must be integers");
            }
            long l = (Long) x;
            long r = (Long) y;
            switch (op) {
                case "+":
                    return l + r;
                case "-":
                    return l - r;
                case "*":
                    return l * r;
                case "/":
                    if (r == 0) {
                        throw new ScriptException(b.line(), b.col(), "division by zero");
                    }
                    return l / r;
                case "%":
                    if (r == 0) {
                        throw new ScriptException(b.line(), b.col(), "division by zero");
                    }
                    return l % r;
                case "<":
                    return l < r;
                case "<=":
                    return l <= r;
                case ">":
                    return l > r;
                default:
                    return l >= r;
            }
        }

        private static boolean valuesEqual(Object x, Object y) {
            if (x instanceof Long a && y instanceof Long b) {
                return a.longValue() == b.longValue();
            }
            if (x instanceof Boolean a && y instanceof Boolean b) {
                return a.booleanValue() == b.booleanValue();
            }
            return x == y;
        }

        private Object call(Call c, Env env) {
            Object f = eval(c.callee(), env);
            int n = c.args().size();
            Object[] args = new Object[n];
            for (int i = 0; i < n; i++) {
                args[i] = eval(c.args().get(i), env);
            }
            if (!(f instanceof Fn fn)) {
                throw new ScriptException(c.line(), c.col(), "not a function");
            }
            List<String> params = fn.def.params();
            if (params.size() != n) {
                throw new ScriptException(c.line(), c.col(),
                    "expected " + params.size() + " arguments but got " + n);
            }
            lastLine = c.line();
            lastCol = c.col();
            if (depth >= MAX_DEPTH) {
                throw new ScriptException(c.line(), c.col(), "call depth exceeded");
            }
            Env scope = new Env(fn.closure);
            for (int i = 0; i < n; i++) {
                scope.declare(params.get(i), args[i]);
            }
            depth++;
            try {
                retVal = NIL;
                if (execList(fn.def.body(), scope)) {
                    Object r = retVal;
                    retVal = NIL;
                    return r;
                }
                return NIL;
            } finally {
                depth--;
            }
        }
    }
}
