package bench.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tree-walking interpreter for a small scripting language. */
public final class Interpreter {
    private static final int MAX_DEPTH = 20000;
    private static final long STACK_BYTES = 1L << 30;

    private Interpreter() {
    }

    public static List<String> run(String source) {
        if (source == null) {
            throw new ScriptException(1, 1, "null source");
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
        if (r instanceof ScriptException se) {
            throw se;
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

    private static List<String> runInternal(String source) {
        List<Token> tokens = lex(source);
        List<Stmt> program = new Parser(tokens).program();
        Ctx ctx = new Ctx();
        Scope global = new Scope(null);
        for (Stmt s : program) {
            s.exec(global, ctx);
        }
        return ctx.out;
    }

    // ------------------------------------------------------------------ lexer

    enum T {
        INT, IDENT, LET, FN, IF, ELSE, WHILE, RETURN, PRINT, TRUE, FALSE, NIL,
        PLUS, MINUS, STAR, SLASH, PERCENT, LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMI,
        ASSIGN, EQ, NE, LT, LE, GT, GE, AND, OR, NOT, ERROR, EOF
    }

    record Token(T type, String text, int line, int col, long value) {
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

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        int line = 1;
        int col = 1;
        while (true) {
            if (i >= n) {
                out.add(new Token(T.EOF, "", line, col, 0));
                return out;
            }
            char c = s.charAt(i);
            if (c == '\n') {
                line++;
                col = 1;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                i++;
                col++;
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                    col++;
                }
                continue;
            }
            int startCol = col;
            if (isDigit(c)) {
                int j = i;
                while (j < n && isDigit(s.charAt(j))) {
                    j++;
                }
                String text = s.substring(i, j);
                try {
                    out.add(new Token(T.INT, text, line, startCol, Long.parseLong(text)));
                } catch (NumberFormatException e) {
                    out.add(new Token(T.ERROR, "integer literal out of range", line, startCol, 0));
                    out.add(new Token(T.EOF, "", line, startCol, 0));
                    return out;
                }
                col += j - i;
                i = j;
                continue;
            }
            if (isIdentStart(c)) {
                int j = i;
                while (j < n && (isIdentStart(s.charAt(j)) || isDigit(s.charAt(j)))) {
                    j++;
                }
                String text = s.substring(i, j);
                out.add(new Token(keyword(text), text, line, startCol, 0));
                col += j - i;
                i = j;
                continue;
            }
            char d = i + 1 < n ? s.charAt(i + 1) : '\0';
            T type;
            int len = 1;
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
                    if (d == '=') {
                        type = T.EQ;
                        len = 2;
                    } else {
                        type = T.ASSIGN;
                    }
                }
                case '!' -> {
                    if (d == '=') {
                        type = T.NE;
                        len = 2;
                    } else {
                        type = T.NOT;
                    }
                }
                case '<' -> {
                    if (d == '=') {
                        type = T.LE;
                        len = 2;
                    } else {
                        type = T.LT;
                    }
                }
                case '>' -> {
                    if (d == '=') {
                        type = T.GE;
                        len = 2;
                    } else {
                        type = T.GT;
                    }
                }
                case '&' -> {
                    if (d == '&') {
                        type = T.AND;
                        len = 2;
                    } else {
                        type = T.ERROR;
                    }
                }
                case '|' -> {
                    if (d == '|') {
                        type = T.OR;
                        len = 2;
                    } else {
                        type = T.ERROR;
                    }
                }
                default -> type = T.ERROR;
            }
            if (type == T.ERROR) {
                out.add(new Token(T.ERROR, "unexpected character '" + c + "'", line, startCol, 0));
                out.add(new Token(T.EOF, "", line, startCol + 1, 0));
                return out;
            }
            out.add(new Token(type, s.substring(i, i + len), line, startCol, 0));
            i += len;
            col += len;
        }
    }

    // ----------------------------------------------------------------- values

    private static final class Nil {
        @Override
        public String toString() {
            return "nil";
        }
    }

    static final Object NIL = new Nil();

    static final class Fn {
        final String name;
        final String[] params;
        final List<Stmt> body;
        final Scope closure;

        Fn(String name, String[] params, List<Stmt> body, Scope closure) {
            this.name = name;
            this.params = params;
            this.body = body;
            this.closure = closure;
        }

        @Override
        public String toString() {
            return name == null ? "<fn>" : "<fn " + name + ">";
        }
    }

    static final class Scope {
        final Scope parent;
        HashMap<String, Object> vars;

        Scope(Scope parent) {
            this.parent = parent;
        }

        boolean has(String name) {
            return vars != null && vars.containsKey(name);
        }

        void declare(String name, Object v) {
            if (vars == null) {
                vars = new HashMap<>(8);
            }
            vars.put(name, v);
        }

        Object get(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.vars != null) {
                    Object v = s.vars.get(name);
                    if (v != null) {
                        return v;
                    }
                }
            }
            return null;
        }

        boolean assign(String name, Object v) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.vars != null && s.vars.containsKey(name)) {
                    s.vars.put(name, v);
                    return true;
                }
            }
            return false;
        }
    }

    static final class Ctx {
        final List<String> out = new ArrayList<>();
        int depth;
    }

    static final class ReturnSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Object value;

        ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    static ScriptException err(Token t, String msg) {
        return new ScriptException(t.line, t.col, msg);
    }

    static String show(Object v) {
        return v.toString();
    }

    // -------------------------------------------------------------------- AST

    abstract static class Expr {
        abstract Object eval(Scope s, Ctx c);
    }

    abstract static class Stmt {
        abstract void exec(Scope s, Ctx c);
    }

    static final class Lit extends Expr {
        final Object value;

        Lit(Object value) {
            this.value = value;
        }

        @Override
        Object eval(Scope s, Ctx c) {
            return value;
        }
    }

    static final class Var extends Expr {
        final Token name;

        Var(Token name) {
            this.name = name;
        }

        @Override
        Object eval(Scope s, Ctx c) {
            Object v = s.get(name.text);
            if (v == null) {
                throw err(name, "undefined variable '" + name.text + "'");
            }
            return v;
        }
    }

    static final class Unary extends Expr {
        final Token op;
        final Expr operand;

        Unary(Token op, Expr operand) {
            this.op = op;
            this.operand = operand;
        }

        @Override
        Object eval(Scope s, Ctx c) {
            Object v = operand.eval(s, c);
            if (op.type == T.MINUS) {
                if (v instanceof Long l) {
                    return -l;
                }
                throw err(op, "operand of '-' must be an integer");
            }
            if (v instanceof Boolean b) {
                return !b;
            }
            throw err(op, "operand of '!' must be a boolean");
        }
    }

    static final class Logical extends Expr {
        final Token op;
        final Expr left;
        final Expr right;

        Logical(Token op, Expr left, Expr right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        Object eval(Scope s, Ctx c) {
            Object l = left.eval(s, c);
            if (!(l instanceof Boolean lb)) {
                throw err(op, "operands of logical operator must be booleans");
            }
            boolean isAnd = op.type == T.AND;
            if (isAnd ? !lb : lb) {
                return lb;
            }
            Object r = right.eval(s, c);
            if (!(r instanceof Boolean)) {
                throw err(op, "operands of logical operator must be booleans");
            }
            return r;
        }
    }

    static final class Binary extends Expr {
        final Token op;
        final Expr left;
        final Expr right;

        Binary(Token op, Expr left, Expr right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        Object eval(Scope s, Ctx c) {
            Object l = left.eval(s, c);
            Object r = right.eval(s, c);
            switch (op.type) {
                case EQ:
                    return l.equals(r);
                case NE:
                    return !l.equals(r);
                default:
                    break;
            }
            if (!(l instanceof Long a) || !(r instanceof Long b)) {
                throw err(op, "operands of '" + op.text + "' must be integers");
            }
            switch (op.type) {
                case PLUS:
                    return a + b;
                case MINUS:
                    return a - b;
                case STAR:
                    return a * b;
                case SLASH:
                    if (b == 0) {
                        throw err(op, "division by zero");
                    }
                    return a / b;
                case PERCENT:
                    if (b == 0) {
                        throw err(op, "division by zero");
                    }
                    return a % b;
                case LT:
                    return a < b;
                case LE:
                    return a <= b;
                case GT:
                    return a > b;
                case GE:
                    return a >= b;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    static final class FnExpr extends Expr {
        final String name;
        final String[] params;
        final List<Stmt> body;

        FnExpr(String name, String[] params, List<Stmt> body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }

        @Override
        Object eval(Scope s, Ctx c) {
            return new Fn(name, params, body, s);
        }
    }

    static final class Call extends Expr {
        final Token paren;
        final Expr callee;
        final Expr[] args;

        Call(Token paren, Expr callee, Expr[] args) {
            this.paren = paren;
            this.callee = callee;
            this.args = args;
        }

        @Override
        Object eval(Scope s, Ctx c) {
            Object f = callee.eval(s, c);
            Object[] vals = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                vals[i] = args[i].eval(s, c);
            }
            if (!(f instanceof Fn fn)) {
                throw err(paren, "not a function");
            }
            if (fn.params.length != vals.length) {
                throw err(paren, "expected " + fn.params.length + " arguments but got " + vals.length);
            }
            if (c.depth >= MAX_DEPTH) {
                throw err(paren, "call stack too deep");
            }
            c.depth++;
            try {
                Scope cs = new Scope(fn.closure);
                for (int i = 0; i < vals.length; i++) {
                    cs.declare(fn.params[i], vals[i]);
                }
                try {
                    List<Stmt> body = fn.body;
                    for (int i = 0, n = body.size(); i < n; i++) {
                        body.get(i).exec(cs, c);
                    }
                } catch (ReturnSignal r) {
                    return r.value;
                }
                return NIL;
            } finally {
                c.depth--;
            }
        }
    }

    static final class Let extends Stmt {
        final Token name;
        final Expr init;

        Let(Token name, Expr init) {
            this.name = name;
            this.init = init;
        }

        @Override
        void exec(Scope s, Ctx c) {
            Object v = init.eval(s, c);
            if (s.has(name.text)) {
                throw err(name, "'" + name.text + "' already declared in this scope");
            }
            s.declare(name.text, v);
        }
    }

    static final class FnDecl extends Stmt {
        final Token name;
        final String[] params;
        final List<Stmt> body;

        FnDecl(Token name, String[] params, List<Stmt> body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }

        @Override
        void exec(Scope s, Ctx c) {
            if (s.has(name.text)) {
                throw err(name, "'" + name.text + "' already declared in this scope");
            }
            s.declare(name.text, new Fn(name.text, params, body, s));
        }
    }

    static final class Assign extends Stmt {
        final Token name;
        final Expr value;

        Assign(Token name, Expr value) {
            this.name = name;
            this.value = value;
        }

        @Override
        void exec(Scope s, Ctx c) {
            Object v = value.eval(s, c);
            if (!s.assign(name.text, v)) {
                throw err(name, "undefined variable '" + name.text + "'");
            }
        }
    }

    static final class Block extends Stmt {
        final List<Stmt> stmts;

        Block(List<Stmt> stmts) {
            this.stmts = stmts;
        }

        @Override
        void exec(Scope s, Ctx c) {
            Scope inner = new Scope(s);
            for (int i = 0, n = stmts.size(); i < n; i++) {
                stmts.get(i).exec(inner, c);
            }
        }
    }

    static final class If extends Stmt {
        final Token kw;
        final Expr cond;
        final Block then;
        final Stmt otherwise;

        If(Token kw, Expr cond, Block then, Stmt otherwise) {
            this.kw = kw;
            this.cond = cond;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        void exec(Scope s, Ctx c) {
            Object v = cond.eval(s, c);
            if (!(v instanceof Boolean b)) {
                throw err(kw, "condition must be a boolean");
            }
            if (b) {
                then.exec(s, c);
            } else if (otherwise != null) {
                otherwise.exec(s, c);
            }
        }
    }

    static final class While extends Stmt {
        final Token kw;
        final Expr cond;
        final Block body;

        While(Token kw, Expr cond, Block body) {
            this.kw = kw;
            this.cond = cond;
            this.body = body;
        }

        @Override
        void exec(Scope s, Ctx c) {
            while (true) {
                Object v = cond.eval(s, c);
                if (!(v instanceof Boolean b)) {
                    throw err(kw, "condition must be a boolean");
                }
                if (!b) {
                    return;
                }
                body.exec(s, c);
            }
        }
    }

    static final class Return extends Stmt {
        final Expr value;

        Return(Expr value) {
            this.value = value;
        }

        @Override
        void exec(Scope s, Ctx c) {
            throw new ReturnSignal(value == null ? NIL : value.eval(s, c));
        }
    }

    static final class Print extends Stmt {
        final Expr value;

        Print(Expr value) {
            this.value = value;
        }

        @Override
        void exec(Scope s, Ctx c) {
            c.out.add(show(value.eval(s, c)));
        }
    }

    static final class ExprStmt extends Stmt {
        final Expr expr;

        ExprStmt(Expr expr) {
            this.expr = expr;
        }

        @Override
        void exec(Scope s, Ctx c) {
            expr.eval(s, c);
        }
    }

    // ----------------------------------------------------------------- parser

    static final class Parser {
        private final List<Token> toks;
        private int pos;
        private int funcDepth;

        Parser(List<Token> toks) {
            this.toks = toks;
        }

        private Token peek() {
            return toks.get(pos);
        }

        private Token peekAt(int k) {
            return toks.get(Math.min(pos + k, toks.size() - 1));
        }

        private Token next() {
            Token t = toks.get(pos);
            if (t.type != T.EOF) {
                pos++;
            }
            return t;
        }

        private ScriptException fail(Token t, String what) {
            if (t.type == T.ERROR) {
                return err(t, t.text);
            }
            return err(t, "expected " + what + " but found '" + (t.type == T.EOF ? "end of input" : t.text) + "'");
        }

        private Token expect(T type, String what) {
            Token t = peek();
            if (t.type != type) {
                throw fail(t, what);
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

        private List<Stmt> blockBody() {
            expect(T.LBRACE, "'{'");
            List<Stmt> out = new ArrayList<>();
            while (peek().type != T.RBRACE) {
                if (peek().type == T.EOF) {
                    throw fail(peek(), "'}'");
                }
                out.add(statement());
            }
            next();
            return out;
        }

        private Block block() {
            return new Block(blockBody());
        }

        private Stmt statement() {
            Token t = peek();
            switch (t.type) {
                case LET: {
                    next();
                    Token name = expect(T.IDENT, "identifier");
                    expect(T.ASSIGN, "'='");
                    Expr e = expr();
                    expect(T.SEMI, "';'");
                    return new Let(name, e);
                }
                case FN:
                    if (peekAt(1).type == T.IDENT) {
                        next();
                        Token name = next();
                        String[] params = params();
                        funcDepth++;
                        List<Stmt> body = blockBody();
                        funcDepth--;
                        return new FnDecl(name, params, body);
                    }
                    break;
                case IF:
                    return ifStatement();
                case WHILE: {
                    next();
                    expect(T.LPAREN, "'('");
                    Expr cond = expr();
                    expect(T.RPAREN, "')'");
                    return new While(t, cond, block());
                }
                case RETURN: {
                    next();
                    if (funcDepth == 0) {
                        throw err(t, "'return' outside function");
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
                case IDENT:
                    if (peekAt(1).type == T.ASSIGN) {
                        next();
                        next();
                        Expr e = expr();
                        expect(T.SEMI, "';'");
                        return new Assign(t, e);
                    }
                    break;
                default:
                    break;
            }
            Expr e = expr();
            expect(T.SEMI, "';'");
            return new ExprStmt(e);
        }

        private Stmt ifStatement() {
            Token kw = next();
            expect(T.LPAREN, "'('");
            Expr cond = expr();
            expect(T.RPAREN, "')'");
            Block then = block();
            Stmt otherwise = null;
            if (peek().type == T.ELSE) {
                next();
                if (peek().type == T.IF) {
                    otherwise = ifStatement();
                } else {
                    otherwise = block();
                }
            }
            return new If(kw, cond, then, otherwise);
        }

        private String[] params() {
            expect(T.LPAREN, "'('");
            List<String> names = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (peek().type != T.RPAREN) {
                while (true) {
                    Token p = expect(T.IDENT, "parameter name");
                    if (!seen.add(p.text)) {
                        throw err(p, "duplicate parameter '" + p.text + "'");
                    }
                    names.add(p.text);
                    if (peek().type == T.COMMA) {
                        next();
                    } else {
                        break;
                    }
                }
            }
            expect(T.RPAREN, "')'");
            return names.toArray(new String[0]);
        }

        Expr expr() {
            Expr left = and();
            while (peek().type == T.OR) {
                Token op = next();
                left = new Logical(op, left, and());
            }
            return left;
        }

        private Expr and() {
            Expr left = equality();
            while (peek().type == T.AND) {
                Token op = next();
                left = new Logical(op, left, equality());
            }
            return left;
        }

        private Expr equality() {
            Expr left = comparison();
            while (peek().type == T.EQ || peek().type == T.NE) {
                Token op = next();
                left = new Binary(op, left, comparison());
            }
            return left;
        }

        private Expr comparison() {
            Expr left = additive();
            while (peek().type == T.LT || peek().type == T.LE || peek().type == T.GT || peek().type == T.GE) {
                Token op = next();
                left = new Binary(op, left, additive());
            }
            return left;
        }

        private Expr additive() {
            Expr left = term();
            while (peek().type == T.PLUS || peek().type == T.MINUS) {
                Token op = next();
                left = new Binary(op, left, term());
            }
            return left;
        }

        private Expr term() {
            Expr left = unary();
            while (peek().type == T.STAR || peek().type == T.SLASH || peek().type == T.PERCENT) {
                Token op = next();
                left = new Binary(op, left, unary());
            }
            return left;
        }

        private Expr unary() {
            Token t = peek();
            if (t.type == T.MINUS || t.type == T.NOT) {
                next();
                return new Unary(t, unary());
            }
            return call();
        }

        private Expr call() {
            Expr e = primary();
            while (peek().type == T.LPAREN) {
                Token paren = next();
                List<Expr> args = new ArrayList<>();
                if (peek().type != T.RPAREN) {
                    while (true) {
                        args.add(expr());
                        if (peek().type == T.COMMA) {
                            next();
                        } else {
                            break;
                        }
                    }
                }
                expect(T.RPAREN, "')'");
                e = new Call(paren, e, args.toArray(new Expr[0]));
            }
            return e;
        }

        private Expr primary() {
            Token t = peek();
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
                    return new Var(t);
                case LPAREN: {
                    next();
                    Expr e = expr();
                    expect(T.RPAREN, "')'");
                    return e;
                }
                case FN: {
                    next();
                    String[] params = params();
                    funcDepth++;
                    List<Stmt> body = blockBody();
                    funcDepth--;
                    return new FnExpr(null, params, body);
                }
                default:
                    throw fail(t, "expression");
            }
        }
    }
}
