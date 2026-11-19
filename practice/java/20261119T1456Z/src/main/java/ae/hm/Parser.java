package ae.hm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Lexer plus recursive-descent parser for the grammar in TASK.md. */
public final class Parser {

    private enum K { INT, IDENT, KEYWORD, SYMBOL, EOF }

    private record Tok(K kind, String text, int pos) {}

    private static final Set<String> KEYWORDS =
        Set.of("let", "rec", "in", "if", "then", "else", "true", "false");

    private final String src;
    private final List<Tok> toks = new ArrayList<>();
    private int i;

    private Parser(String src) {
        this.src = src;
        lex();
    }

    /** True for the operator names that the parser turns into builtin variables. */
    static boolean isOperator(String name) {
        return switch (name) {
            case "+", "-", "*", "==" -> true;
            default -> false;
        };
    }

    public static Expr parse(String source) {
        Objects.requireNonNull(source, "source");
        Parser p = new Parser(source);
        Expr e = p.expr();
        Tok t = p.peek();
        if (t.kind() != K.EOF) {
            throw p.error(t, "unexpected " + describe(t));
        }
        return e;
    }

    /** 1-based line and column of a source offset. */
    static int[] lineCol(String src, int offset) {
        int line = 1;
        int col = 1;
        for (int k = 0; k < offset && k < src.length(); k++) {
            if (src.charAt(k) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new int[] {line, col};
    }

    private ParseException error(Tok at, String message) {
        return error(at.pos(), message);
    }

    private ParseException error(int offset, String message) {
        int[] lc = lineCol(src, offset);
        return new ParseException(lc[0], lc[1], message);
    }

    private static String describe(Tok t) {
        return t.kind() == K.EOF ? "end of input" : "'" + t.text() + "'";
    }

    // ---- lexer ----

    private void lex() {
        int n = src.length();
        int k = 0;
        while (k < n) {
            char c = src.charAt(k);
            if (Character.isWhitespace(c)) {
                k++;
            } else if (c >= '0' && c <= '9') {
                int start = k;
                while (k < n && src.charAt(k) >= '0' && src.charAt(k) <= '9') {
                    k++;
                }
                String text = src.substring(start, k);
                if (text.length() > 9) {
                    throw error(start, "integer literal too large: " + text);
                }
                toks.add(new Tok(K.INT, text, start));
            } else if (isIdentStart(c)) {
                int start = k;
                while (k < n && (isIdentStart(src.charAt(k)) || Character.isDigit(src.charAt(k)) || src.charAt(k) == '\'')) {
                    k++;
                }
                String text = src.substring(start, k);
                toks.add(new Tok(KEYWORDS.contains(text) ? K.KEYWORD : K.IDENT, text, start));
            } else if (c == '-' && k + 1 < n && src.charAt(k + 1) == '>') {
                toks.add(new Tok(K.SYMBOL, "->", k));
                k += 2;
            } else if (c == '=' && k + 1 < n && src.charAt(k + 1) == '=') {
                toks.add(new Tok(K.SYMBOL, "==", k));
                k += 2;
            } else if ("\\=+-*(),".indexOf(c) >= 0) {
                toks.add(new Tok(K.SYMBOL, String.valueOf(c), k));
                k++;
            } else {
                throw error(k, "unexpected character '" + c + "'");
            }
        }
        toks.add(new Tok(K.EOF, "", n));
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    // ---- parser ----

    private Tok peek() { return toks.get(i); }

    private Tok next() { return toks.get(i++); }

    private boolean at(K kind, String text) {
        Tok t = peek();
        return t.kind() == kind && t.text().equals(text);
    }

    private Tok expect(K kind, String text) {
        if (!at(kind, text)) {
            throw error(peek(), "expected '" + text + "' but found " + describe(peek()));
        }
        return next();
    }

    private Tok ident() {
        Tok t = peek();
        if (t.kind() != K.IDENT) {
            throw error(t, "expected an identifier but found " + describe(t));
        }
        return next();
    }

    private Expr expr() {
        Tok t = peek();
        if (at(K.SYMBOL, "\\")) {
            next();
            List<Tok> params = new ArrayList<>();
            params.add(ident());
            while (peek().kind() == K.IDENT) {
                params.add(next());
            }
            expect(K.SYMBOL, "->");
            return lambdas(params, expr(), t.pos());
        }
        if (at(K.KEYWORD, "let")) {
            next();
            boolean rec = false;
            if (at(K.KEYWORD, "rec")) {
                next();
                rec = true;
            }
            Tok name = ident();
            List<Tok> params = new ArrayList<>();
            while (peek().kind() == K.IDENT) {
                params.add(next());
            }
            expect(K.SYMBOL, "=");
            Expr value = lambdas(params, expr(), name.pos());
            expect(K.KEYWORD, "in");
            Expr body = expr();
            return new Expr.Let(name.text(), value, body, rec, t.pos());
        }
        if (at(K.KEYWORD, "if")) {
            next();
            Expr c = expr();
            expect(K.KEYWORD, "then");
            Expr a = expr();
            expect(K.KEYWORD, "else");
            Expr b = expr();
            return new Expr.If(c, a, b, t.pos());
        }
        Expr left = add();
        if (at(K.SYMBOL, "==")) {
            Tok op = next();
            return binary(op, left, add());
        }
        return left;
    }

    private static Expr lambdas(List<Tok> params, Expr body, int pos) {
        Expr result = body;
        for (int k = params.size() - 1; k >= 0; k--) {
            result = new Expr.Lam(params.get(k).text(), result, k == 0 ? pos : params.get(k).pos());
        }
        return result;
    }

    private static Expr binary(Tok op, Expr l, Expr r) {
        Expr f = new Expr.App(new Expr.Var(op.text(), op.pos()), l, op.pos());
        return new Expr.App(f, r, op.pos());
    }

    private Expr add() {
        Expr left = mul();
        while (at(K.SYMBOL, "+") || at(K.SYMBOL, "-")) {
            Tok op = next();
            left = binary(op, left, mul());
        }
        return left;
    }

    private Expr mul() {
        Expr left = app();
        while (at(K.SYMBOL, "*")) {
            Tok op = next();
            left = binary(op, left, app());
        }
        return left;
    }

    private boolean startsAtom() {
        Tok t = peek();
        return t.kind() == K.INT || t.kind() == K.IDENT
            || (t.kind() == K.KEYWORD && (t.text().equals("true") || t.text().equals("false")))
            || (t.kind() == K.SYMBOL && t.text().equals("("));
    }

    private Expr app() {
        Expr f = atom();
        while (startsAtom()) {
            f = new Expr.App(f, atom(), f.position());
        }
        return f;
    }

    private Expr atom() {
        Tok t = peek();
        switch (t.kind()) {
            case INT:
                next();
                return new Expr.IntLit(Integer.parseInt(t.text()), t.pos());
            case IDENT:
                next();
                return new Expr.Var(t.text(), t.pos());
            case KEYWORD:
                if (t.text().equals("true") || t.text().equals("false")) {
                    next();
                    return new Expr.BoolLit(t.text().equals("true"), t.pos());
                }
                throw error(t, "unexpected keyword '" + t.text() + "'");
            case SYMBOL:
                if (t.text().equals("(")) {
                    next();
                    Expr first = expr();
                    if (at(K.SYMBOL, ",")) {
                        next();
                        Expr second = expr();
                        expect(K.SYMBOL, ")");
                        return new Expr.Pair(first, second, t.pos());
                    }
                    expect(K.SYMBOL, ")");
                    return first;
                }
                throw error(t, "unexpected " + describe(t));
            default:
                throw error(t, "unexpected " + describe(t));
        }
    }
}
