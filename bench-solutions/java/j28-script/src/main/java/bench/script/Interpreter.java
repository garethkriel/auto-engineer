package bench.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tree-walking interpreter for a small language. */
public final class Interpreter {
    private static final int MAX_DEPTH = 20_000;
    private static final long STACK_BYTES = 512L * 1024 * 1024;

    private Interpreter() { }

    public static List<String> run(String source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        Object[] box = new Object[2];
        Thread t = new Thread(null, () -> {
            try {
                box[0] = runInternal(source);
            } catch (Throwable e) {
                box[1] = e;
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
        if (box[1] instanceof ScriptException se) {
            throw se;
        }
        if (box[1] instanceof StackOverflowError) {
            throw new ScriptException(1, 1, "stack overflow");
        }
        if (box[1] instanceof RuntimeException re) {
            throw re;
        }
        if (box[1] instanceof Error er) {
            throw er;
        }
        @SuppressWarnings("unchecked")
        List<String> out = (List<String>) box[0];
        return out;
    }

    private static List<String> runInternal(String source) {
        Parser parser = new Parser(new Lexer(source));
        List<Stmt> program = parser.parseProgram();
        Runtime rt = new Runtime();
        Env global = new Env(null);
        for (Stmt s : program) {
            Object r = s.exec(global, rt);
            if (r != null) {
                break; // cannot happen: return outside function is a syntax error
            }
        }
        return rt.out;
    }

    // ------------------------------------------------------------------ runtime values

    private static final class Nil {
        @Override public String toString() {
            return "nil";
        }
    }

    private static final Nil NIL = new Nil();

    private static final class Fn {
        final String name;
        final List<String> params;
        final List<Stmt> body;
        final Env closure;

        Fn(String name, List<String> params, List<Stmt> body, Env closure) {
            this.name = name;
            this.params = params;
            this.body = body;
            this.closure = closure;
        }
    }

    private static final class Runtime {
        final List<String> out = new ArrayList<>();
        int depth;
    }

    private static final class Env {
        final Env parent;
        Map<String, Object> vars;

        Env(Env parent) {
            this.parent = parent;
        }

        boolean declare(String name, Object v) {
            if (vars == null) {
                vars = new HashMap<>(8);
            } else if (vars.containsKey(name)) {
                return false;
            }
            vars.put(name, v);
            return true;
        }

        Env owner(String name) {
            for (Env e = this; e != null; e = e.parent) {
                if (e.vars != null && e.vars.containsKey(name)) {
                    return e;
                }
            }
            return null;
        }
    }

    private static final class Ret {
        final Object value;

        Ret(Object value) {
            this.value = value;
        }
    }

    private static String show(Object v) {
        if (v instanceof Fn f) {
            return f.name == null ? "<fn>" : "<fn " + f.name + ">";
        }
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ lexer

    private record Tok(String kind, String text, long value, int line, int col) { }

    private static final class Lexer {
        private final String src;
        private int pos;
        private int line = 1;
        private int col = 1;

        Lexer(String src) {
            this.src = src;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isIdStart(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }

        private void adv() {
            if (src.charAt(pos) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            pos++;
        }

        private char peekc(int off) {
            int i = pos + off;
            return i < src.length() ? src.charAt(i) : '\0';
        }

        Tok next() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    adv();
                } else if (c == '/' && peekc(1) == '/') {
                    while (pos < src.length() && src.charAt(pos) != '\n') {
                        adv();
                    }
                } else {
                    break;
                }
            }
            if (pos >= src.length()) {
                return new Tok("EOF", "", 0, line, col);
            }
            int l = line;
            int cl = col;
            char c = src.charAt(pos);
            if (isDigit(c)) {
                int s = pos;
                while (pos < src.length() && isDigit(src.charAt(pos))) {
                    adv();
                }
                String text = src.substring(s, pos);
                try {
                    return new Tok("INT", text, Long.parseLong(text), l, cl);
                } catch (NumberFormatException e) {
                    throw new ScriptException(l, cl, "integer literal too large");
                }
            }
            if (isIdStart(c)) {
                int s = pos;
                while (pos < src.length() && (isIdStart(src.charAt(pos)) || isDigit(src.charAt(pos)))) {
                    adv();
                }
                String text = src.substring(s, pos);
                switch (text) {
                    case "let", "fn", "if", "else", "while", "return", "print", "true", "false", "nil":
                        return new Tok(text, text, 0, l, cl);
                    default:
                        return new Tok("IDENT", text, 0, l, cl);
                }
            }
            String op;
            switch (c) {
                case '+', '-', '*', '/', '%', '(', ')', '{', '}', ',', ';' -> op = String.valueOf(c);
                case '=', '!', '<', '>' -> op = peekc(1) == '=' ? c + "=" : String.valueOf(c);
                case '&' -> {
                    if (peekc(1) != '&') {
                        throw new ScriptException(l, cl, "unexpected character '&'");
                    }
                    op = "&&";
                }
                case '|' -> {
                    if (peekc(1) != '|') {
                        throw new ScriptException(l, cl, "unexpected character '|'");
                    }
                    op = "||";
                }
                default -> throw new ScriptException(l, cl, "unexpected character '" + c + "'");
            }
            for (int i = 0; i < op.length(); i++) {
                adv();
            }
            return new Tok(op, op, 0, l, cl);
        }
    }

    // ------------------------------------------------------------------ AST

    private abstract static class Expr {
        abstract Object eval(Env env, Runtime rt);
    }

    private abstract static class Stmt {
        /** Returns null normally, or a Ret when a return statement ran. */
        abstract Object exec(Env env, Runtime rt);
    }

    private static ScriptException err(Tok t, String msg) {
        return new ScriptException(t.line(), t.col(), msg);
    }

    private static final class Lit extends Expr {
        final Object v;

        Lit(Object v) {
            this.v = v;
        }

        @Override Object eval(Env env, Runtime rt) {
            return v;
        }
    }

    private static final class Var extends Expr {
        final Tok name;

        Var(Tok name) {
            this.name = name;
        }

        @Override Object eval(Env env, Runtime rt) {
            for (Env e = env; e != null; e = e.parent) {
                if (e.vars != null) {
                    Object v = e.vars.get(name.text());
                    if (v != null) {
                        return v;
                    }
                }
            }
            throw err(name, "undefined variable '" + name.text() + "'");
        }
    }

    private static final class FnExpr extends Expr {
        final List<String> params;
        final List<Stmt> body;

        FnExpr(List<String> params, List<Stmt> body) {
            this.params = params;
            this.body = body;
        }

        @Override Object eval(Env env, Runtime rt) {
            return new Fn(null, params, body, env);
        }
    }

    private static final class Unary extends Expr {
        final Tok op;
        final Expr operand;

        Unary(Tok op, Expr operand) {
            this.op = op;
            this.operand = operand;
        }

        @Override Object eval(Env env, Runtime rt) {
            Object v = operand.eval(env, rt);
            if (op.kind().equals("-")) {
                if (!(v instanceof Long l)) {
                    throw err(op, "operand of '-' must be an integer");
                }
                return -l;
            }
            if (!(v instanceof Boolean b)) {
                throw err(op, "operand of '!' must be a boolean");
            }
            return !b;
        }
    }

    private static final class Logic extends Expr {
        final Tok op;
        final boolean isAnd;
        final Expr left;
        final Expr right;

        Logic(Tok op, Expr left, Expr right) {
            this.op = op;
            this.isAnd = op.kind().equals("&&");
            this.left = left;
            this.right = right;
        }

        @Override Object eval(Env env, Runtime rt) {
            Object l = left.eval(env, rt);
            if (!(l instanceof Boolean lb)) {
                throw err(op, "operand of '" + op.kind() + "' must be a boolean");
            }
            if (isAnd ? !lb : lb) {
                return lb;
            }
            Object r = right.eval(env, rt);
            if (!(r instanceof Boolean)) {
                throw err(op, "operand of '" + op.kind() + "' must be a boolean");
            }
            return r;
        }
    }

    private static final class Binary extends Expr {
        final Tok op;
        final char code;
        final Expr left;
        final Expr right;

        Binary(Tok op, Expr left, Expr right) {
            this.op = op;
            String k = op.kind();
            this.code = switch (k) {
                case "+" -> '+';
                case "-" -> '-';
                case "*" -> '*';
                case "/" -> '/';
                case "%" -> '%';
                case "<" -> '<';
                case "<=" -> 'l';
                case ">" -> '>';
                case ">=" -> 'g';
                case "==" -> 'e';
                default -> 'n';
            };
            this.left = left;
            this.right = right;
        }

        @Override Object eval(Env env, Runtime rt) {
            Object l = left.eval(env, rt);
            Object r = right.eval(env, rt);
            if (code == 'e') {
                return same(l, r);
            }
            if (code == 'n') {
                return !same(l, r);
            }
            if (!(l instanceof Long a) || !(r instanceof Long b)) {
                throw err(op, "operands of '" + op.kind() + "' must be integers");
            }
            switch (code) {
                case '+': return a + b;
                case '-': return a - b;
                case '*': return a * b;
                case '/':
                    if (b == 0) {
                        throw err(op, "division by zero");
                    }
                    return a / b;
                case '%':
                    if (b == 0) {
                        throw err(op, "division by zero");
                    }
                    return a % b;
                case '<': return a < b;
                case 'l': return a <= b;
                case '>': return a > b;
                default: return a >= b;
            }
        }

        private static boolean same(Object l, Object r) {
            if (l instanceof Long a) {
                return r instanceof Long b && a.longValue() == b.longValue();
            }
            if (l instanceof Boolean a) {
                return r instanceof Boolean b && a.booleanValue() == b.booleanValue();
            }
            return l == r;
        }
    }

    private static final class Call extends Expr {
        final Tok paren;
        final Expr callee;
        final List<Expr> args;

        Call(Tok paren, Expr callee, List<Expr> args) {
            this.paren = paren;
            this.callee = callee;
            this.args = args;
        }

        @Override Object eval(Env env, Runtime rt) {
            Object f = callee.eval(env, rt);
            int n = args.size();
            Object[] vals = new Object[n];
            for (int i = 0; i < n; i++) {
                vals[i] = args.get(i).eval(env, rt);
            }
            if (!(f instanceof Fn fn)) {
                throw err(paren, "can only call functions");
            }
            if (fn.params.size() != n) {
                throw err(paren, "expected " + fn.params.size() + " arguments but got " + n);
            }
            if (rt.depth >= MAX_DEPTH) {
                throw err(paren, "call depth exceeded");
            }
            Env scope = new Env(fn.closure);
            for (int i = 0; i < n; i++) {
                scope.declare(fn.params.get(i), vals[i]);
            }
            rt.depth++;
            try {
                for (Stmt s : fn.body) {
                    Object r = s.exec(scope, rt);
                    if (r != null) {
                        return ((Ret) r).value;
                    }
                }
            } catch (StackOverflowError so) {
                throw err(paren, "call depth exceeded");
            } finally {
                rt.depth--;
            }
            return NIL;
        }
    }

    private static final class LetStmt extends Stmt {
        final Tok name;
        final Expr init;

        LetStmt(Tok name, Expr init) {
            this.name = name;
            this.init = init;
        }

        @Override Object exec(Env env, Runtime rt) {
            Object v = init.eval(env, rt);
            if (!env.declare(name.text(), v)) {
                throw err(name, "'" + name.text() + "' is already declared in this scope");
            }
            return null;
        }
    }

    private static final class FnDecl extends Stmt {
        final Tok name;
        final List<String> params;
        final List<Stmt> body;

        FnDecl(Tok name, List<String> params, List<Stmt> body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }

        @Override Object exec(Env env, Runtime rt) {
            if (!env.declare(name.text(), new Fn(name.text(), params, body, env))) {
                throw err(name, "'" + name.text() + "' is already declared in this scope");
            }
            return null;
        }
    }

    private static final class Assign extends Stmt {
        final Tok name;
        final Expr value;

        Assign(Tok name, Expr value) {
            this.name = name;
            this.value = value;
        }

        @Override Object exec(Env env, Runtime rt) {
            Object v = value.eval(env, rt);
            Env o = env.owner(name.text());
            if (o == null) {
                throw err(name, "undefined variable '" + name.text() + "'");
            }
            o.vars.put(name.text(), v);
            return null;
        }
    }

    private static Object execBlock(List<Stmt> body, Env env, Runtime rt) {
        Env scope = new Env(env);
        for (Stmt s : body) {
            Object r = s.exec(scope, rt);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static final class IfStmt extends Stmt {
        final Tok kw;
        final Expr cond;
        final List<Stmt> then;
        final List<Stmt> elseBlock; // may be null
        final Stmt elseIf;          // may be null

        IfStmt(Tok kw, Expr cond, List<Stmt> then, List<Stmt> elseBlock, Stmt elseIf) {
            this.kw = kw;
            this.cond = cond;
            this.then = then;
            this.elseBlock = elseBlock;
            this.elseIf = elseIf;
        }

        @Override Object exec(Env env, Runtime rt) {
            Object c = cond.eval(env, rt);
            if (!(c instanceof Boolean b)) {
                throw err(kw, "condition must be a boolean");
            }
            if (b) {
                return execBlock(then, env, rt);
            }
            if (elseBlock != null) {
                return execBlock(elseBlock, env, rt);
            }
            if (elseIf != null) {
                return elseIf.exec(env, rt);
            }
            return null;
        }
    }

    private static final class WhileStmt extends Stmt {
        final Tok kw;
        final Expr cond;
        final List<Stmt> body;

        WhileStmt(Tok kw, Expr cond, List<Stmt> body) {
            this.kw = kw;
            this.cond = cond;
            this.body = body;
        }

        @Override Object exec(Env env, Runtime rt) {
            while (true) {
                Object c = cond.eval(env, rt);
                if (!(c instanceof Boolean b)) {
                    throw err(kw, "condition must be a boolean");
                }
                if (!b) {
                    return null;
                }
                Object r = execBlock(body, env, rt);
                if (r != null) {
                    return r;
                }
            }
        }
    }

    private static final class ReturnStmt extends Stmt {
        final Expr value; // may be null

        ReturnStmt(Expr value) {
            this.value = value;
        }

        @Override Object exec(Env env, Runtime rt) {
            return new Ret(value == null ? NIL : value.eval(env, rt));
        }
    }

    private static final class PrintStmt extends Stmt {
        final Expr value;

        PrintStmt(Expr value) {
            this.value = value;
        }

        @Override Object exec(Env env, Runtime rt) {
            rt.out.add(show(value.eval(env, rt)));
            return null;
        }
    }

    private static final class ExprStmt extends Stmt {
        final Expr value;

        ExprStmt(Expr value) {
            this.value = value;
        }

        @Override Object exec(Env env, Runtime rt) {
            value.eval(env, rt);
            return null;
        }
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        private final Lexer lexer;
        private final List<Tok> buf = new ArrayList<>();
        private int fnDepth;

        Parser(Lexer lexer) {
            this.lexer = lexer;
        }

        private Tok peek(int k) {
            while (buf.size() <= k) {
                buf.add(lexer.next());
            }
            return buf.get(k);
        }

        private Tok cur() {
            return peek(0);
        }

        private Tok advance() {
            Tok t = peek(0);
            if (!t.kind().equals("EOF")) {
                buf.remove(0);
            }
            return t;
        }

        private boolean at(String kind) {
            return cur().kind().equals(kind);
        }

        private Tok expect(String kind) {
            Tok t = cur();
            if (!t.kind().equals(kind)) {
                throw err(t, "expected '" + kind + "' but found '" + (t.kind().equals("EOF") ? "end of input" : t.text()) + "'");
            }
            return advance();
        }

        List<Stmt> parseProgram() {
            List<Stmt> out = new ArrayList<>();
            while (!at("EOF")) {
                out.add(statement());
            }
            return out;
        }

        private List<Stmt> block() {
            expect("{");
            List<Stmt> out = new ArrayList<>();
            while (!at("}")) {
                if (at("EOF")) {
                    throw err(cur(), "expected '}' but found end of input");
                }
                out.add(statement());
            }
            advance();
            return out;
        }

        private List<String> params() {
            expect("(");
            List<String> ps = new ArrayList<>();
            if (!at(")")) {
                while (true) {
                    Tok id = expect("IDENT");
                    if (ps.contains(id.text())) {
                        throw err(id, "duplicate parameter '" + id.text() + "'");
                    }
                    ps.add(id.text());
                    if (at(",")) {
                        advance();
                    } else {
                        break;
                    }
                }
            }
            expect(")");
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
            Tok t = cur();
            switch (t.kind()) {
                case "let": {
                    advance();
                    Tok name = expect("IDENT");
                    expect("=");
                    Expr e = expr();
                    expect(";");
                    return new LetStmt(name, e);
                }
                case "fn":
                    if (peek(1).kind().equals("IDENT")) {
                        advance();
                        Tok name = advance();
                        List<String> ps = params();
                        List<Stmt> body = functionBody();
                        return new FnDecl(name, ps, body);
                    }
                    break;
                case "if":
                    return ifStatement();
                case "while": {
                    advance();
                    expect("(");
                    Expr c = expr();
                    expect(")");
                    List<Stmt> body = block();
                    return new WhileStmt(t, c, body);
                }
                case "return": {
                    if (fnDepth == 0) {
                        throw err(t, "'return' outside of a function");
                    }
                    advance();
                    Expr e = null;
                    if (!at(";")) {
                        e = expr();
                    }
                    expect(";");
                    return new ReturnStmt(e);
                }
                case "print": {
                    advance();
                    Expr e = expr();
                    expect(";");
                    return new PrintStmt(e);
                }
                case "IDENT":
                    if (peek(1).kind().equals("=")) {
                        advance();
                        advance();
                        Expr e = expr();
                        expect(";");
                        return new Assign(t, e);
                    }
                    break;
                default:
                    break;
            }
            Expr e = expr();
            expect(";");
            return new ExprStmt(e);
        }

        private Stmt ifStatement() {
            Tok kw = expect("if");
            expect("(");
            Expr c = expr();
            expect(")");
            List<Stmt> then = block();
            List<Stmt> elseBlock = null;
            Stmt elseIf = null;
            if (at("else")) {
                advance();
                if (at("if")) {
                    elseIf = ifStatement();
                } else {
                    elseBlock = block();
                }
            }
            return new IfStmt(kw, c, then, elseBlock, elseIf);
        }

        private Expr expr() {
            Expr left = and();
            while (at("||")) {
                Tok op = advance();
                left = new Logic(op, left, and());
            }
            return left;
        }

        private Expr and() {
            Expr left = equality();
            while (at("&&")) {
                Tok op = advance();
                left = new Logic(op, left, equality());
            }
            return left;
        }

        private Expr equality() {
            Expr left = comparison();
            while (at("==") || at("!=")) {
                Tok op = advance();
                left = new Binary(op, left, comparison());
            }
            return left;
        }

        private Expr comparison() {
            Expr left = additive();
            while (at("<") || at("<=") || at(">") || at(">=")) {
                Tok op = advance();
                left = new Binary(op, left, additive());
            }
            return left;
        }

        private Expr additive() {
            Expr left = term();
            while (at("+") || at("-")) {
                Tok op = advance();
                left = new Binary(op, left, term());
            }
            return left;
        }

        private Expr term() {
            Expr left = unary();
            while (at("*") || at("/") || at("%")) {
                Tok op = advance();
                left = new Binary(op, left, unary());
            }
            return left;
        }

        private Expr unary() {
            if (at("-") || at("!")) {
                Tok op = advance();
                return new Unary(op, unary());
            }
            return call();
        }

        private Expr call() {
            Expr e = primary();
            while (at("(")) {
                Tok paren = advance();
                List<Expr> args = new ArrayList<>();
                if (!at(")")) {
                    while (true) {
                        args.add(expr());
                        if (at(",")) {
                            advance();
                        } else {
                            break;
                        }
                    }
                }
                expect(")");
                e = new Call(paren, e, args);
            }
            return e;
        }

        private Expr primary() {
            Tok t = cur();
            switch (t.kind()) {
                case "INT":
                    advance();
                    return new Lit(t.value());
                case "true":
                    advance();
                    return new Lit(Boolean.TRUE);
                case "false":
                    advance();
                    return new Lit(Boolean.FALSE);
                case "nil":
                    advance();
                    return new Lit(NIL);
                case "IDENT":
                    advance();
                    return new Var(t);
                case "(": {
                    advance();
                    Expr e = expr();
                    expect(")");
                    return e;
                }
                case "fn": {
                    advance();
                    List<String> ps = params();
                    List<Stmt> body = functionBody();
                    return new FnExpr(ps, body);
                }
                default:
                    throw err(t, "unexpected '" + (t.kind().equals("EOF") ? "end of input" : t.text()) + "'");
            }
        }
    }
}
