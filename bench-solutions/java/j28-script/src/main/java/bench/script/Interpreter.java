package bench.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tree-walking interpreter for a small language. */
public final class Interpreter {
    private Interpreter() { }

    private static final int MAX_DEPTH = 20_000;
    private static final long STACK_BYTES = 1L << 29;

    public static List<String> run(String source) {
        if (source == null) {
            throw new IllegalArgumentException("source");
        }
        Object[] box = new Object[1];
        Thread t = new Thread(null, () -> {
            try {
                box[0] = runInternal(source);
            } catch (Throwable e) {
                box[0] = e;
            }
        }, "script-runner", STACK_BYTES);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptException(1, 1, "interrupted");
        }
        Object r = box[0];
        if (r instanceof ScriptException se) {
            throw new ScriptException(se.line(), se.column(), stripPrefix(se));
        }
        if (r instanceof StackOverflowError) {
            throw new ScriptException(1, 1, "stack overflow");
        }
        if (r instanceof RuntimeException re) {
            throw re;
        }
        if (r instanceof Error er) {
            throw er;
        }
        @SuppressWarnings("unchecked")
        List<String> out = (List<String>) r;
        return out;
    }

    private static String stripPrefix(ScriptException se) {
        String m = se.getMessage();
        String p = se.line() + ":" + se.column() + ": ";
        return m.startsWith(p) ? m.substring(p.length()) : m;
    }

    private static List<String> runInternal(String source) {
        try {
            List<Tok> toks = lex(source);
            List<Stmt> prog = new Parser(toks).program();
            Machine m = new Machine();
            Env global = new Env(null);
            m.execList(prog, global);
            return m.out;
        } catch (StackOverflowError e) {
            throw new ScriptException(1, 1, "stack overflow");
        }
    }

    // ---------------------------------------------------------------- lexer

    private enum T {
        INT, IDENT, LET, FN, IF, ELSE, WHILE, RETURN, PRINT, TRUE, FALSE, NIL,
        PLUS, MINUS, STAR, SLASH, PERCENT, LP, RP, LB, RB, COMMA, SEMI, ASSIGN,
        EQ, NE, LT, LE, GT, GE, AND, OR, NOT, EOF, ERROR
    }

    private record Tok(T type, String text, long value, int line, int col, String err) { }

    private static final Map<String, T> KEYWORDS = Map.of(
        "let", T.LET, "fn", T.FN, "if", T.IF, "else", T.ELSE, "while", T.WHILE,
        "return", T.RETURN, "print", T.PRINT, "true", T.TRUE, "false", T.FALSE, "nil", T.NIL);

    private static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int line = 1;
        int col = 1;
        int n = s.length();
        while (true) {
            // skip whitespace and comments
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\n') {
                    i++; line++; col = 1;
                } else if (c == ' ' || c == '\t' || c == '\r') {
                    i++; col++;
                } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                    while (i < n && s.charAt(i) != '\n') {
                        i++; col++;
                    }
                } else {
                    break;
                }
            }
            if (i >= n) {
                out.add(new Tok(T.EOF, "", 0, line, col, null));
                return out;
            }
            char c = s.charAt(i);
            int sl = line;
            int sc = col;
            if (c >= '0' && c <= '9') {
                int j = i;
                while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') j++;
                String text = s.substring(i, j);
                try {
                    out.add(new Tok(T.INT, text, Long.parseLong(text), sl, sc, null));
                } catch (NumberFormatException e) {
                    out.add(new Tok(T.ERROR, text, 0, sl, sc, "integer literal too large"));
                    return out;
                }
                col += j - i;
                i = j;
            } else if (isIdStart(c)) {
                int j = i;
                while (j < n && (isIdStart(s.charAt(j)) || (s.charAt(j) >= '0' && s.charAt(j) <= '9'))) j++;
                String text = s.substring(i, j);
                out.add(new Tok(KEYWORDS.getOrDefault(text, T.IDENT), text, 0, sl, sc, null));
                col += j - i;
                i = j;
            } else {
                char d = i + 1 < n ? s.charAt(i + 1) : '\0';
                T type = null;
                int len = 1;
                switch (c) {
                    case '+' -> type = T.PLUS;
                    case '-' -> type = T.MINUS;
                    case '*' -> type = T.STAR;
                    case '/' -> type = T.SLASH;
                    case '%' -> type = T.PERCENT;
                    case '(' -> type = T.LP;
                    case ')' -> type = T.RP;
                    case '{' -> type = T.LB;
                    case '}' -> type = T.RB;
                    case ',' -> type = T.COMMA;
                    case ';' -> type = T.SEMI;
                    case '=' -> { if (d == '=') { type = T.EQ; len = 2; } else type = T.ASSIGN; }
                    case '!' -> { if (d == '=') { type = T.NE; len = 2; } else type = T.NOT; }
                    case '<' -> { if (d == '=') { type = T.LE; len = 2; } else type = T.LT; }
                    case '>' -> { if (d == '=') { type = T.GE; len = 2; } else type = T.GT; }
                    case '&' -> { if (d == '&') { type = T.AND; len = 2; } }
                    case '|' -> { if (d == '|') { type = T.OR; len = 2; } }
                    default -> { }
                }
                if (type == null) {
                    out.add(new Tok(T.ERROR, String.valueOf(c), 0, sl, sc, "unexpected character '" + c + "'"));
                    return out;
                }
                out.add(new Tok(type, s.substring(i, i + len), 0, sl, sc, null));
                i += len;
                col += len;
            }
        }
    }

    private static boolean isIdStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    // ------------------------------------------------------------------ AST

    private sealed interface Expr permits Lit, Var, Unary, Binary, Logic, Call, FnExpr { }

    private record Lit(Object value) implements Expr { }

    private record Var(String name, int line, int col) implements Expr { }

    private record Unary(T op, Expr operand, int line, int col) implements Expr { }

    private record Binary(T op, Expr left, Expr right, int line, int col) implements Expr { }

    private record Logic(boolean isAnd, Expr left, Expr right, int line, int col) implements Expr { }

    private record Call(Expr callee, List<Expr> args, int line, int col) implements Expr { }

    private record FnExpr(String name, List<String> params, List<Stmt> body) implements Expr { }

    private sealed interface Stmt permits Let, FnDecl, If, While, Return, Print, Assign, ExprStmt { }

    private record Let(String name, Expr init, int line, int col) implements Stmt { }

    private record FnDecl(String name, FnExpr fn, int line, int col) implements Stmt { }

    private record If(Expr cond, List<Stmt> then, List<Stmt> otherwise, int line, int col) implements Stmt { }

    private record While(Expr cond, List<Stmt> body, int line, int col) implements Stmt { }

    private record Return(Expr value) implements Stmt { }

    private record Print(Expr value) implements Stmt { }

    private record Assign(String name, Expr value, int line, int col) implements Stmt { }

    private record ExprStmt(Expr expr) implements Stmt { }

    // --------------------------------------------------------------- parser

    private static final class Parser {
        private final List<Tok> toks;
        private int pos;
        private int fnDepth;

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        private Tok peek() {
            return toks.get(pos);
        }

        private Tok peekAt(int k) {
            int i = Math.min(pos + k, toks.size() - 1);
            return toks.get(i);
        }

        private Tok next() {
            Tok t = toks.get(pos);
            if (t.type != T.EOF && t.type != T.ERROR) pos++;
            return t;
        }

        private ScriptException err(Tok t, String msg) {
            if (t.type == T.ERROR) {
                return new ScriptException(t.line, t.col, t.err);
            }
            return new ScriptException(t.line, t.col, msg);
        }

        private Tok expect(T type, String what) {
            Tok t = peek();
            if (t.type != type) {
                throw err(t, "expected " + what);
            }
            return next();
        }

        List<Stmt> program() {
            List<Stmt> out = new ArrayList<>();
            while (peek().type != T.EOF) {
                out.add(statement());
            }
            return out;
        }

        private List<Stmt> block() {
            expect(T.LB, "'{'");
            List<Stmt> out = new ArrayList<>();
            while (peek().type != T.RB) {
                if (peek().type == T.EOF || peek().type == T.ERROR) {
                    throw err(peek(), "expected '}'");
                }
                out.add(statement());
            }
            next();
            return out;
        }

        private Stmt statement() {
            Tok t = peek();
            switch (t.type) {
                case LET: {
                    next();
                    Tok name = expect(T.IDENT, "identifier");
                    expect(T.ASSIGN, "'='");
                    Expr e = expr();
                    expect(T.SEMI, "';'");
                    return new Let(name.text, e, name.line, name.col);
                }
                case FN: {
                    if (peekAt(1).type == T.IDENT) {
                        next();
                        Tok name = next();
                        FnExpr f = fnRest(name.text);
                        return new FnDecl(name.text, f, name.line, name.col);
                    }
                    break;
                }
                case IF:
                    return ifStmt();
                case WHILE: {
                    next();
                    expect(T.LP, "'('");
                    Expr c = expr();
                    expect(T.RP, "')'");
                    List<Stmt> body = block();
                    return new While(c, body, t.line, t.col);
                }
                case RETURN: {
                    next();
                    if (fnDepth == 0) {
                        throw new ScriptException(t.line, t.col, "return outside function");
                    }
                    Expr e = null;
                    if (peek().type != T.SEMI) {
                        e = expr();
                    }
                    expect(T.SEMI, "';'");
                    return new Return(e);
                }
                case PRINT: {
                    next();
                    Expr e = expr();
                    expect(T.SEMI, "';'");
                    return new Print(e);
                }
                case IDENT: {
                    if (peekAt(1).type == T.ASSIGN) {
                        next();
                        next();
                        Expr e = expr();
                        expect(T.SEMI, "';'");
                        return new Assign(t.text, e, t.line, t.col);
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

        private Stmt ifStmt() {
            Tok t = next();
            expect(T.LP, "'('");
            Expr c = expr();
            expect(T.RP, "')'");
            List<Stmt> then = block();
            List<Stmt> other = null;
            if (peek().type == T.ELSE) {
                next();
                if (peek().type == T.IF) {
                    other = new ArrayList<>();
                    other.add(ifStmt());
                } else {
                    other = block();
                }
            }
            return new If(c, then, other, t.line, t.col);
        }

        /** Parses "(" params ")" block, the "fn" and optional name already consumed. */
        private FnExpr fnRest(String name) {
            expect(T.LP, "'('");
            List<String> params = new ArrayList<>();
            if (peek().type != T.RP) {
                while (true) {
                    Tok p = expect(T.IDENT, "parameter name");
                    if (params.contains(p.text)) {
                        throw new ScriptException(p.line, p.col, "duplicate parameter '" + p.text + "'");
                    }
                    params.add(p.text);
                    if (peek().type == T.COMMA) {
                        next();
                    } else {
                        break;
                    }
                }
            }
            expect(T.RP, "')'");
            fnDepth++;
            List<Stmt> body = block();
            fnDepth--;
            return new FnExpr(name, params, body);
        }

        Expr expr() {
            Expr left = and();
            while (peek().type == T.OR) {
                Tok op = next();
                Expr right = and();
                left = new Logic(false, left, right, op.line, op.col);
            }
            return left;
        }

        private Expr and() {
            Expr left = equality();
            while (peek().type == T.AND) {
                Tok op = next();
                Expr right = equality();
                left = new Logic(true, left, right, op.line, op.col);
            }
            return left;
        }

        private Expr equality() {
            Expr left = comparison();
            while (peek().type == T.EQ || peek().type == T.NE) {
                Tok op = next();
                left = new Binary(op.type, left, comparison(), op.line, op.col);
            }
            return left;
        }

        private Expr comparison() {
            Expr left = additive();
            while (peek().type == T.LT || peek().type == T.LE || peek().type == T.GT || peek().type == T.GE) {
                Tok op = next();
                left = new Binary(op.type, left, additive(), op.line, op.col);
            }
            return left;
        }

        private Expr additive() {
            Expr left = term();
            while (peek().type == T.PLUS || peek().type == T.MINUS) {
                Tok op = next();
                left = new Binary(op.type, left, term(), op.line, op.col);
            }
            return left;
        }

        private Expr term() {
            Expr left = unary();
            while (peek().type == T.STAR || peek().type == T.SLASH || peek().type == T.PERCENT) {
                Tok op = next();
                left = new Binary(op.type, left, unary(), op.line, op.col);
            }
            return left;
        }

        private Expr unary() {
            Tok t = peek();
            if (t.type == T.MINUS || t.type == T.NOT) {
                next();
                return new Unary(t.type, unary(), t.line, t.col);
            }
            return call();
        }

        private Expr call() {
            Expr e = primary();
            while (peek().type == T.LP) {
                Tok lp = next();
                List<Expr> args = new ArrayList<>();
                if (peek().type != T.RP) {
                    while (true) {
                        args.add(expr());
                        if (peek().type == T.COMMA) {
                            next();
                        } else {
                            break;
                        }
                    }
                }
                expect(T.RP, "')'");
                e = new Call(e, args, lp.line, lp.col);
            }
            return e;
        }

        private Expr primary() {
            Tok t = peek();
            switch (t.type) {
                case INT:
                    next();
                    return new Lit(t.value);
                case TRUE:
                    next();
                    return new Lit(Boolean.TRUE);
                case FALSE:
                    next();
                    return new Lit(Boolean.FALSE);
                case NIL:
                    next();
                    return new Lit(NIL);
                case IDENT:
                    next();
                    return new Var(t.text, t.line, t.col);
                case LP: {
                    next();
                    Expr e = expr();
                    expect(T.RP, "')'");
                    return e;
                }
                case FN:
                    next();
                    return fnRest(null);
                default:
                    throw err(t, "unexpected token");
            }
        }
    }

    // -------------------------------------------------------------- runtime

    private static final Object NIL = new Object() {
        @Override
        public String toString() {
            return "nil";
        }
    };

    private static final class Fn {
        final FnExpr def;
        final Env env;

        Fn(FnExpr def, Env env) {
            this.def = def;
            this.env = env;
        }
    }

    private static final class Env {
        final Env parent;
        private HashMap<String, Object> vars;

        Env(Env parent) {
            this.parent = parent;
        }

        boolean declaredHere(String name) {
            return vars != null && vars.containsKey(name);
        }

        void declare(String name, Object v) {
            if (vars == null) vars = new HashMap<>(8);
            vars.put(name, v);
        }

        /** Returns the env that declares name, or null. */
        Env find(String name) {
            for (Env e = this; e != null; e = e.parent) {
                if (e.vars != null && e.vars.containsKey(name)) return e;
            }
            return null;
        }

        Object lookupLocal(String name) {
            return vars.get(name);
        }

        void set(String name, Object v) {
            vars.put(name, v);
        }
    }

    private static final class Machine {
        final List<String> out = new ArrayList<>();
        int depth;
        boolean returning;
        Object retVal = NIL;

        /** Executes statements in the given scope; returns true if a return is propagating. */
        boolean execList(List<Stmt> stmts, Env env) {
            for (int i = 0, n = stmts.size(); i < n; i++) {
                if (exec(stmts.get(i), env)) return true;
            }
            return false;
        }

        boolean exec(Stmt s, Env env) {
            if (s instanceof Let l) {
                Object v = eval(l.init, env);
                if (env.declaredHere(l.name)) {
                    throw new ScriptException(l.line, l.col, "'" + l.name + "' already declared");
                }
                env.declare(l.name, v);
            } else if (s instanceof Assign a) {
                Object v = eval(a.value, env);
                Env e = env.find(a.name);
                if (e == null) {
                    throw new ScriptException(a.line, a.col, "undeclared variable '" + a.name + "'");
                }
                e.set(a.name, v);
            } else if (s instanceof ExprStmt es) {
                eval(es.expr, env);
            } else if (s instanceof Print p) {
                out.add(show(eval(p.value, env)));
            } else if (s instanceof If f) {
                Object c = eval(f.cond, env);
                if (!(c instanceof Boolean b)) {
                    throw new ScriptException(f.line, f.col, "condition must be a boolean");
                }
                if (b) {
                    return execList(f.then, new Env(env));
                } else if (f.otherwise != null) {
                    return execList(f.otherwise, new Env(env));
                }
            } else if (s instanceof While w) {
                while (true) {
                    Object c = eval(w.cond, env);
                    if (!(c instanceof Boolean b)) {
                        throw new ScriptException(w.line, w.col, "condition must be a boolean");
                    }
                    if (!b) break;
                    if (execList(w.body, new Env(env))) return true;
                }
            } else if (s instanceof Return r) {
                retVal = r.value == null ? NIL : eval(r.value, env);
                return true;
            } else if (s instanceof FnDecl d) {
                if (env.declaredHere(d.name)) {
                    throw new ScriptException(d.line, d.col, "'" + d.name + "' already declared");
                }
                env.declare(d.name, new Fn(d.fn, env));
            }
            return false;
        }

        Object eval(Expr e, Env env) {
            if (e instanceof Lit l) {
                return l.value;
            } else if (e instanceof Var v) {
                Env d = env.find(v.name);
                if (d == null) {
                    throw new ScriptException(v.line, v.col, "undeclared variable '" + v.name + "'");
                }
                return d.lookupLocal(v.name);
            } else if (e instanceof Binary b) {
                Object l = eval(b.left, env);
                Object r = eval(b.right, env);
                return binary(b, l, r);
            } else if (e instanceof Logic g) {
                Object l = eval(g.left, env);
                if (!(l instanceof Boolean lb)) {
                    throw new ScriptException(g.line, g.col, "operand must be a boolean");
                }
                if (g.isAnd ? !lb : lb) {
                    return lb;
                }
                Object r = eval(g.right, env);
                if (!(r instanceof Boolean)) {
                    throw new ScriptException(g.line, g.col, "operand must be a boolean");
                }
                return r;
            } else if (e instanceof Unary u) {
                Object v = eval(u.operand, env);
                if (u.op == T.MINUS) {
                    if (!(v instanceof Long x)) {
                        throw new ScriptException(u.line, u.col, "operand must be an integer");
                    }
                    return -x;
                }
                if (!(v instanceof Boolean x)) {
                    throw new ScriptException(u.line, u.col, "operand must be a boolean");
                }
                return !x;
            } else if (e instanceof Call c) {
                return call(c, env);
            } else if (e instanceof FnExpr f) {
                return new Fn(f, env);
            }
            throw new IllegalStateException();
        }

        Object call(Call c, Env env) {
            Object callee = eval(c.callee, env);
            Object[] args = new Object[c.args.size()];
            for (int i = 0; i < args.length; i++) {
                args[i] = eval(c.args.get(i), env);
            }
            if (!(callee instanceof Fn f)) {
                throw new ScriptException(c.line, c.col, "not a function");
            }
            if (f.def.params.size() != args.length) {
                throw new ScriptException(c.line, c.col, "wrong number of arguments");
            }
            if (depth >= MAX_DEPTH) {
                throw new ScriptException(c.line, c.col, "call depth exceeded");
            }
            Env scope = new Env(f.env);
            for (int i = 0; i < args.length; i++) {
                scope.declare(f.def.params.get(i), args[i]);
            }
            depth++;
            try {
                retVal = NIL;
                if (execList(f.def.body, scope)) {
                    Object r = retVal;
                    retVal = NIL;
                    return r;
                }
                return NIL;
            } finally {
                depth--;
            }
        }

        Object binary(Binary b, Object l, Object r) {
            switch (b.op) {
                case EQ:
                    return equal(l, r);
                case NE:
                    return !equal(l, r);
                default:
                    break;
            }
            if (!(l instanceof Long x) || !(r instanceof Long y)) {
                throw new ScriptException(b.line, b.col, "operands must be integers");
            }
            switch (b.op) {
                case PLUS: return x + y;
                case MINUS: return x - y;
                case STAR: return x * y;
                case SLASH:
                    if (y == 0) throw new ScriptException(b.line, b.col, "division by zero");
                    return x / y;
                case PERCENT:
                    if (y == 0) throw new ScriptException(b.line, b.col, "division by zero");
                    return x % y;
                case LT: return x < y;
                case LE: return x <= y;
                case GT: return x > y;
                case GE: return x >= y;
                default: throw new IllegalStateException();
            }
        }

        static boolean equal(Object l, Object r) {
            if (l instanceof Long a && r instanceof Long b) return a.longValue() == b.longValue();
            if (l instanceof Boolean a && r instanceof Boolean b) return a.booleanValue() == b.booleanValue();
            return l == r;
        }

        static String show(Object v) {
            if (v instanceof Fn f) {
                return f.def.name == null ? "<fn>" : "<fn " + f.def.name + ">";
            }
            return v.toString();
        }
    }
}
