package bench.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tree-walking interpreter for a small language. */
public final class Interpreter {
    private static final int MAX_DEPTH = 20_000;
    private static final long STACK_BYTES = 1L << 30;

    private Interpreter() {
    }

    /** Parses and runs {@code source}; returns the printed lines. */
    public static List<String> run(String source) {
        if (source == null) {
            throw new IllegalArgumentException("source is null");
        }
        Object[] result = new Object[1];
        Thread t = new Thread(null, () -> {
            try {
                result[0] = new Runner(source).go();
            } catch (Throwable e) {
                result[0] = e;
            }
        }, "script", STACK_BYTES);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptException(1, 1, "interrupted");
        }
        if (result[0] instanceof RuntimeException re) {
            throw re;
        }
        if (result[0] instanceof Error er) {
            throw er;
        }
        @SuppressWarnings("unchecked")
        List<String> out = (List<String>) result[0];
        return out;
    }

    // ------------------------------------------------------------------ tokens

    private enum T {
        INT, IDENT, LET, FN, IF, ELSE, WHILE, RETURN, PRINT, TRUE, FALSE, NIL,
        PLUS, MINUS, STAR, SLASH, PERCENT, LP, RP, LB, RB, COMMA, SEMI, ASSIGN,
        EQ, NE, LT, LE, GT, GE, AND, OR, NOT, ERROR, EOF
    }

    private record Tok(T type, String text, long value, int line, int col) {
    }

    private static List<Tok> lex(String src) {
        int[] cp = src.codePoints().toArray();
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int line = 1;
        int col = 1;
        int n = cp.length;
        while (true) {
            // skip whitespace and comments
            while (i < n) {
                int c = cp[i];
                if (c == '\n') {
                    i++;
                    line++;
                    col = 1;
                } else if (c == ' ' || c == '\t' || c == '\r') {
                    i++;
                    col++;
                } else if (c == '/' && i + 1 < n && cp[i + 1] == '/') {
                    while (i < n && cp[i] != '\n') {
                        i++;
                        col++;
                    }
                } else {
                    break;
                }
            }
            if (i >= n) {
                out.add(new Tok(T.EOF, "", 0, line, col));
                return out;
            }
            int c = cp[i];
            int sl = line;
            int sc = col;
            if (c >= '0' && c <= '9') {
                int j = i;
                long v = 0;
                boolean over = false;
                while (j < n && cp[j] >= '0' && cp[j] <= '9') {
                    int d = cp[j] - '0';
                    if (v > (Long.MAX_VALUE - d) / 10) {
                        over = true;
                    } else {
                        v = v * 10 + d;
                    }
                    j++;
                }
                col += j - i;
                i = j;
                if (over) {
                    out.add(new Tok(T.ERROR, "integer too large", 0, sl, sc));
                    out.add(new Tok(T.EOF, "", 0, line, col));
                    return out;
                }
                out.add(new Tok(T.INT, "", v, sl, sc));
                continue;
            }
            if (isIdStart(c)) {
                int j = i;
                StringBuilder sb = new StringBuilder();
                while (j < n && (isIdStart(cp[j]) || (cp[j] >= '0' && cp[j] <= '9'))) {
                    sb.append((char) cp[j]);
                    j++;
                }
                col += j - i;
                i = j;
                String s = sb.toString();
                out.add(new Tok(keyword(s), s, 0, sl, sc));
                continue;
            }
            T two = null;
            int next = i + 1 < n ? cp[i + 1] : -1;
            if (c == '=' && next == '=') {
                two = T.EQ;
            } else if (c == '!' && next == '=') {
                two = T.NE;
            } else if (c == '<' && next == '=') {
                two = T.LE;
            } else if (c == '>' && next == '=') {
                two = T.GE;
            } else if (c == '&' && next == '&') {
                two = T.AND;
            } else if (c == '|' && next == '|') {
                two = T.OR;
            }
            if (two != null) {
                out.add(new Tok(two, "", 0, sl, sc));
                i += 2;
                col += 2;
                continue;
            }
            T one = switch (c) {
                case '+' -> T.PLUS;
                case '-' -> T.MINUS;
                case '*' -> T.STAR;
                case '/' -> T.SLASH;
                case '%' -> T.PERCENT;
                case '(' -> T.LP;
                case ')' -> T.RP;
                case '{' -> T.LB;
                case '}' -> T.RB;
                case ',' -> T.COMMA;
                case ';' -> T.SEMI;
                case '=' -> T.ASSIGN;
                case '<' -> T.LT;
                case '>' -> T.GT;
                case '!' -> T.NOT;
                default -> null;
            };
            if (one == null) {
                out.add(new Tok(T.ERROR, "unexpected character", 0, sl, sc));
                out.add(new Tok(T.EOF, "", 0, line, col + 1));
                return out;
            }
            out.add(new Tok(one, "", 0, sl, sc));
            i++;
            col++;
        }
    }

    private static boolean isIdStart(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static T keyword(String s) {
        return switch (s) {
            case "let" -> T.LET;
            case "fn" -> T.FN;
            case "if" -> T.IF;
            case "else" -> T.ELSE;
            case "while" -> T.WHILE;
            case "return" -> T.RETURN;
            case "print" -> T.PRINT;
            case "true" -> T.TRUE;
            case "false" -> T.FALSE;
            case "nil" -> T.NIL;
            default -> T.IDENT;
        };
    }

    // ------------------------------------------------------------------ values

    private static final class Nil {
        static final Nil NIL = new Nil();
    }

    private static final class Scope {
        final Scope parent;
        HashMap<String, Object> vars;

        Scope(Scope parent) {
            this.parent = parent;
        }
    }

    private static final class Fn {
        final String name;
        final String[] params;
        final Stmt[] body;
        final Scope closure;

        Fn(String name, String[] params, Stmt[] body, Scope closure) {
            this.name = name;
            this.params = params;
            this.body = body;
            this.closure = closure;
        }
    }

    // --------------------------------------------------------------------- AST

    private abstract static class Expr {
        abstract Object eval(Runner r, Scope s);
    }

    private abstract static class Stmt {
        /** Returns true when a {@code return} executed (value in runner.ret). */
        abstract boolean exec(Runner r, Scope s);
    }

    private static ScriptException err(Tok t, String msg) {
        return new ScriptException(t.line, t.col, msg);
    }

    private static ScriptException err(int line, int col, String msg) {
        return new ScriptException(line, col, msg);
    }

    private static String show(Object v) {
        if (v instanceof Fn f) {
            return f.name == null ? "<fn>" : "<fn " + f.name + ">";
        }
        return String.valueOf(v instanceof Nil ? "nil" : v);
    }

    // -------------------------------------------------------------- expressions

    private static final class Lit extends Expr {
        final Object v;

        Lit(Object v) {
            this.v = v;
        }

        @Override
        Object eval(Runner r, Scope s) {
            return v;
        }
    }

    private static final class Var extends Expr {
        final String name;
        final int line;
        final int col;

        Var(String name, int line, int col) {
            this.name = name;
            this.line = line;
            this.col = col;
        }

        @Override
        Object eval(Runner r, Scope s) {
            for (Scope c = s; c != null; c = c.parent) {
                if (c.vars != null) {
                    Object v = c.vars.get(name);
                    if (v != null) {
                        return v;
                    }
                }
            }
            throw err(line, col, "undeclared variable " + name);
        }
    }

    private static final class Unary extends Expr {
        final Tok op;
        final Expr e;

        Unary(Tok op, Expr e) {
            this.op = op;
            this.e = e;
        }

        @Override
        Object eval(Runner r, Scope s) {
            Object v = e.eval(r, s);
            if (op.type == T.MINUS) {
                if (v instanceof Long l) {
                    return -l;
                }
                throw err(op, "operand of - must be an integer");
            }
            if (v instanceof Boolean b) {
                return !b;
            }
            throw err(op, "operand of ! must be a boolean");
        }
    }

    private static final class Logic extends Expr {
        final Tok op;
        final Expr l;
        final Expr r;
        final boolean and;

        Logic(Tok op, Expr l, Expr r) {
            this.op = op;
            this.l = l;
            this.r = r;
            this.and = op.type == T.AND;
        }

        @Override
        Object eval(Runner run, Scope s) {
            Object a = l.eval(run, s);
            if (!(a instanceof Boolean x)) {
                throw err(op, "operand must be a boolean");
            }
            if (and ? !x : x) {
                return x;
            }
            Object b = r.eval(run, s);
            if (!(b instanceof Boolean)) {
                throw err(op, "operand must be a boolean");
            }
            return b;
        }
    }

    private static final class Binary extends Expr {
        final Tok op;
        final Expr l;
        final Expr r;

        Binary(Tok op, Expr l, Expr r) {
            this.op = op;
            this.l = l;
            this.r = r;
        }

        @Override
        Object eval(Runner run, Scope s) {
            Object a = l.eval(run, s);
            Object b = r.eval(run, s);
            T t = op.type;
            if (t == T.EQ) {
                return equal(a, b);
            }
            if (t == T.NE) {
                return !equal(a, b);
            }
            if (!(a instanceof Long x) || !(b instanceof Long y)) {
                throw err(op, "operands must be integers");
            }
            long p = x;
            long q = y;
            switch (t) {
                case PLUS:
                    return p + q;
                case MINUS:
                    return p - q;
                case STAR:
                    return p * q;
                case SLASH:
                    if (q == 0) {
                        throw err(op, "division by zero");
                    }
                    return p / q;
                case PERCENT:
                    if (q == 0) {
                        throw err(op, "division by zero");
                    }
                    return p % q;
                case LT:
                    return p < q;
                case LE:
                    return p <= q;
                case GT:
                    return p > q;
                case GE:
                    return p >= q;
                default:
                    throw new IllegalStateException();
            }
        }

        static boolean equal(Object a, Object b) {
            if (a == b) {
                return true;
            }
            if (a instanceof Long || a instanceof Boolean) {
                return a.equals(b);
            }
            return false;
        }
    }

    private static final class Call extends Expr {
        final Tok paren;
        final Expr callee;
        final Expr[] args;

        Call(Tok paren, Expr callee, Expr[] args) {
            this.paren = paren;
            this.callee = callee;
            this.args = args;
        }

        @Override
        Object eval(Runner r, Scope s) {
            Object f = callee.eval(r, s);
            Object[] vals = new Object[args.length];
            for (int i = 0; i < vals.length; i++) {
                vals[i] = args[i].eval(r, s);
            }
            if (!(f instanceof Fn fn)) {
                throw err(paren, "not a function");
            }
            if (fn.params.length != vals.length) {
                throw err(paren, "wrong number of arguments");
            }
            if (r.depth >= MAX_DEPTH) {
                throw err(paren, "recursion too deep");
            }
            Scope cs = new Scope(fn.closure);
            if (vals.length > 0) {
                cs.vars = new HashMap<>();
                for (int i = 0; i < vals.length; i++) {
                    cs.vars.put(fn.params[i], vals[i]);
                }
            }
            r.depth++;
            try {
                for (Stmt st : fn.body) {
                    if (st.exec(r, cs)) {
                        Object v = r.ret;
                        r.ret = null;
                        return v;
                    }
                }
                return Nil.NIL;
            } catch (StackOverflowError e) {
                throw err(paren, "stack overflow");
            } finally {
                r.depth--;
            }
        }
    }

    private static final class FnExpr extends Expr {
        final String name;
        final String[] params;
        final Stmt[] body;

        FnExpr(String name, String[] params, Stmt[] body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }

        @Override
        Object eval(Runner r, Scope s) {
            return new Fn(name, params, body, s);
        }
    }

    // -------------------------------------------------------------- statements

    private static void declare(Scope s, Tok name, String id, Object v) {
        if (s.vars == null) {
            s.vars = new HashMap<>();
        } else if (s.vars.containsKey(id)) {
            throw err(name, "already declared: " + id);
        }
        s.vars.put(id, v);
    }

    private static final class Let extends Stmt {
        final Tok name;
        final Expr e;

        Let(Tok name, Expr e) {
            this.name = name;
            this.e = e;
        }

        @Override
        boolean exec(Runner r, Scope s) {
            Object v = e.eval(r, s);
            declare(s, name, name.text, v);
            return false;
        }
    }

    private static final class FnDecl extends Stmt {
        final Tok name;
        final String[] params;
        final Stmt[] body;

        FnDecl(Tok name, String[] params, Stmt[] body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }

        @Override
        boolean exec(Runner r, Scope s) {
            declare(s, name, name.text, new Fn(name.text, params, body, s));
            return false;
        }
    }

    private static boolean execAll(Runner r, Stmt[] body, Scope s) {
        for (Stmt st : body) {
            if (st.exec(r, s)) {
                return true;
            }
        }
        return false;
    }

    private static final class If extends Stmt {
        final Tok kw;
        final Expr cond;
        final Stmt[] then;
        final Stmt other; // Block-like (If or BlockStmt) or null

        If(Tok kw, Expr cond, Stmt[] then, Stmt other) {
            this.kw = kw;
            this.cond = cond;
            this.then = then;
            this.other = other;
        }

        @Override
        boolean exec(Runner r, Scope s) {
            Object c = cond.eval(r, s);
            if (!(c instanceof Boolean b)) {
                throw err(kw, "condition must be a boolean");
            }
            if (b) {
                return execAll(r, then, new Scope(s));
            }
            return other != null && other.exec(r, s);
        }
    }

    private static final class BlockStmt extends Stmt {
        final Stmt[] body;

        BlockStmt(Stmt[] body) {
            this.body = body;
        }

        @Override
        boolean exec(Runner r, Scope s) {
            return execAll(r, body, new Scope(s));
        }
    }

    private static final class While extends Stmt {
        final Tok kw;
        final Expr cond;
        final Stmt[] body;

        While(Tok kw, Expr cond, Stmt[] body) {
            this.kw = kw;
            this.cond = cond;
            this.body = body;
        }

        @Override
        boolean exec(Runner r, Scope s) {
            while (true) {
                Object c = cond.eval(r, s);
                if (!(c instanceof Boolean b)) {
                    throw err(kw, "condition must be a boolean");
                }
                if (!b) {
                    return false;
                }
                if (execAll(r, body, new Scope(s))) {
                    return true;
                }
            }
        }
    }

    private static final class Return extends Stmt {
        final Expr e;

        Return(Expr e) {
            this.e = e;
        }

        @Override
        boolean exec(Runner r, Scope s) {
            r.ret = e == null ? Nil.NIL : e.eval(r, s);
            return true;
        }
    }

    private static final class Print extends Stmt {
        final Expr e;

        Print(Expr e) {
            this.e = e;
        }

        @Override
        boolean exec(Runner r, Scope s) {
            r.out.add(show(e.eval(r, s)));
            return false;
        }
    }

    private static final class Assign extends Stmt {
        final Tok name;
        final Expr e;

        Assign(Tok name, Expr e) {
            this.name = name;
            this.e = e;
        }

        @Override
        boolean exec(Runner r, Scope s) {
            Object v = e.eval(r, s);
            for (Scope c = s; c != null; c = c.parent) {
                if (c.vars != null && c.vars.containsKey(name.text)) {
                    c.vars.put(name.text, v);
                    return false;
                }
            }
            throw err(name, "undeclared variable " + name.text);
        }
    }

    private static final class ExprStmt extends Stmt {
        final Expr e;

        ExprStmt(Expr e) {
            this.e = e;
        }

        @Override
        boolean exec(Runner r, Scope s) {
            e.eval(r, s);
            return false;
        }
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        final List<Tok> toks;
        int pos;
        int fnDepth;

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        Tok peek() {
            return toks.get(pos);
        }

        Tok peek(int k) {
            return toks.get(Math.min(pos + k, toks.size() - 1));
        }

        Tok next() {
            Tok t = toks.get(pos);
            if (t.type != T.EOF) {
                pos++;
            }
            return t;
        }

        boolean at(T t) {
            return peek().type == t;
        }

        Tok expect(T t, String what) {
            Tok k = peek();
            if (k.type != t) {
                throw err(k, "expected " + what);
            }
            return next();
        }

        Stmt[] program() {
            List<Stmt> out = new ArrayList<>();
            while (!at(T.EOF)) {
                out.add(statement());
            }
            return out.toArray(new Stmt[0]);
        }

        Stmt[] block() {
            expect(T.LB, "{");
            List<Stmt> out = new ArrayList<>();
            while (!at(T.RB)) {
                if (at(T.EOF)) {
                    throw err(peek(), "expected }");
                }
                out.add(statement());
            }
            next();
            return out.toArray(new Stmt[0]);
        }

        String[] params() {
            expect(T.LP, "(");
            List<String> ps = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (!at(T.RP)) {
                while (true) {
                    Tok id = expect(T.IDENT, "parameter name");
                    if (!seen.add(id.text)) {
                        throw err(id, "duplicate parameter " + id.text);
                    }
                    ps.add(id.text);
                    if (at(T.COMMA)) {
                        next();
                    } else {
                        break;
                    }
                }
            }
            expect(T.RP, ")");
            return ps.toArray(new String[0]);
        }

        Stmt[] functionBody() {
            fnDepth++;
            try {
                return block();
            } finally {
                fnDepth--;
            }
        }

        Stmt statement() {
            Tok t = peek();
            switch (t.type) {
                case LET: {
                    next();
                    Tok name = expect(T.IDENT, "name");
                    expect(T.ASSIGN, "=");
                    Expr e = expr();
                    expect(T.SEMI, ";");
                    return new Let(name, e);
                }
                case FN:
                    if (peek(1).type == T.IDENT) {
                        next();
                        Tok name = next();
                        String[] ps = params();
                        Stmt[] body = functionBody();
                        return new FnDecl(name, ps, body);
                    }
                    break;
                case IF:
                    return ifStatement();
                case WHILE: {
                    next();
                    expect(T.LP, "(");
                    Expr c = expr();
                    expect(T.RP, ")");
                    return new While(t, c, block());
                }
                case RETURN: {
                    if (fnDepth == 0) {
                        throw err(t, "return outside function");
                    }
                    next();
                    Expr e = null;
                    if (!at(T.SEMI)) {
                        e = expr();
                    }
                    expect(T.SEMI, ";");
                    return new Return(e);
                }
                case PRINT: {
                    next();
                    Expr e = expr();
                    expect(T.SEMI, ";");
                    return new Print(e);
                }
                case IDENT:
                    if (peek(1).type == T.ASSIGN) {
                        next();
                        next();
                        Expr e = expr();
                        expect(T.SEMI, ";");
                        return new Assign(t, e);
                    }
                    break;
                default:
                    break;
            }
            Expr e = expr();
            expect(T.SEMI, ";");
            return new ExprStmt(e);
        }

        Stmt ifStatement() {
            Tok kw = expect(T.IF, "if");
            expect(T.LP, "(");
            Expr c = expr();
            expect(T.RP, ")");
            Stmt[] then = block();
            Stmt other = null;
            if (at(T.ELSE)) {
                next();
                if (at(T.IF)) {
                    other = ifStatement();
                } else {
                    other = new BlockStmt(block());
                }
            }
            return new If(kw, c, then, other);
        }

        Expr expr() {
            Expr l = and();
            while (at(T.OR)) {
                Tok op = next();
                l = new Logic(op, l, and());
            }
            return l;
        }

        Expr and() {
            Expr l = equality();
            while (at(T.AND)) {
                Tok op = next();
                l = new Logic(op, l, equality());
            }
            return l;
        }

        Expr equality() {
            Expr l = comparison();
            while (at(T.EQ) || at(T.NE)) {
                Tok op = next();
                l = new Binary(op, l, comparison());
            }
            return l;
        }

        Expr comparison() {
            Expr l = additive();
            while (at(T.LT) || at(T.LE) || at(T.GT) || at(T.GE)) {
                Tok op = next();
                l = new Binary(op, l, additive());
            }
            return l;
        }

        Expr additive() {
            Expr l = term();
            while (at(T.PLUS) || at(T.MINUS)) {
                Tok op = next();
                l = new Binary(op, l, term());
            }
            return l;
        }

        Expr term() {
            Expr l = unary();
            while (at(T.STAR) || at(T.SLASH) || at(T.PERCENT)) {
                Tok op = next();
                l = new Binary(op, l, unary());
            }
            return l;
        }

        Expr unary() {
            if (at(T.MINUS) || at(T.NOT)) {
                Tok op = next();
                return new Unary(op, unary());
            }
            return call();
        }

        Expr call() {
            Expr e = primary();
            while (at(T.LP)) {
                Tok paren = next();
                List<Expr> args = new ArrayList<>();
                if (!at(T.RP)) {
                    while (true) {
                        args.add(expr());
                        if (at(T.COMMA)) {
                            next();
                        } else {
                            break;
                        }
                    }
                }
                expect(T.RP, ")");
                e = new Call(paren, e, args.toArray(new Expr[0]));
            }
            return e;
        }

        Expr primary() {
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
                    return new Lit(Nil.NIL);
                case IDENT:
                    next();
                    return new Var(t.text, t.line, t.col);
                case LP: {
                    next();
                    Expr e = expr();
                    expect(T.RP, ")");
                    return e;
                }
                case FN: {
                    next();
                    String[] ps = params();
                    Stmt[] body = functionBody();
                    return new FnExpr(null, ps, body);
                }
                default:
                    throw err(t, "unexpected token");
            }
        }
    }

    // ------------------------------------------------------------------ runner

    private static final class Runner {
        final String source;
        final List<String> out = new ArrayList<>();
        Object ret;
        int depth;

        Runner(String source) {
            this.source = source;
        }

        List<String> go() {
            List<Tok> toks = lex(source);
            Parser p = new Parser(toks);
            Stmt[] prog;
            try {
                prog = p.program();
            } catch (StackOverflowError e) {
                throw err(p.peek(), "nesting too deep");
            }
            Scope global = new Scope(null);
            for (Stmt s : prog) {
                s.exec(this, global);
            }
            return out;
        }
    }
}
