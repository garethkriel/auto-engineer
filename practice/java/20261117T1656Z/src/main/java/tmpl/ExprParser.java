package tmpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Tokenises and parses the expression language used inside tags and outputs.
 *
 * <pre>
 * or     := and ('or' and)*
 * and    := not ('and' not)*
 * not    := 'not' not | cmp
 * cmp    := primary (('=='|'!='|'&lt;'|'&lt;='|'&gt;'|'&gt;=') primary)?
 * primary:= '(' or ')' | STRING | NUMBER | true | false | null | path
 * output := or ('|' NAME ['(' literal (',' literal)* ')'])*
 * </pre>
 */
final class ExprParser {
    static final int MAX_DEPTH = 100;
    static final Set<String> RESERVED = Set.of("and", "or", "not", "in", "true", "false", "null");

    private enum T { IDENT, STRING, NUMBER, OP, LPAREN, RPAREN, PIPE, COMMA, EOF }

    private record Tok(T type, String text, int pos, Object value) {
    }

    private final String src;
    private final List<Tok> toks = new ArrayList<>();
    private int p;
    private int depth;

    /**
     * @param src     the whole template source (for error positions)
     * @param content the text between the delimiters
     * @param from    index in {@code content} where the expression starts
     * @param base    absolute offset of {@code content}'s first character
     */
    ExprParser(String src, String content, int from, int base) {
        this.src = src;
        tokenize(content, from, base);
    }

    // ---- tokeniser --------------------------------------------------------------

    private void tokenize(String s, int from, int base) {
        int i = from;
        int lastEnd = base + from;
        int n = s.length();
        while (true) {
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            char c = s.charAt(i);
            int start = i;
            if (Character.isLetter(c) || c == '_') {
                i = scanIdent(s, i, base);
                add(T.IDENT, s.substring(start, i), base + start, null);
            } else if (c >= '0' && c <= '9') {
                while (i < n && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                if (i < n && s.charAt(i) == '.') {
                    if (i + 1 >= n || !Character.isDigit(s.charAt(i + 1))) {
                        throw Positions.syntax(src, base + i, "expected a digit after '.' in number");
                    }
                    i++;
                    while (i < n && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                add(T.NUMBER, s.substring(start, i), base + start, new BigDecimal(s.substring(start, i)));
            } else if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == c) {
                        closed = true;
                        i++;
                        break;
                    }
                    if (d == '\\') {
                        if (i + 1 >= n) {
                            break;
                        }
                        char e = s.charAt(i + 1);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case '\\', '\'', '"' -> sb.append(e);
                            default -> throw Positions.syntax(src, base + i, "invalid escape '\\" + e + "'");
                        }
                        i += 2;
                    } else {
                        sb.append(d);
                        i++;
                    }
                }
                if (!closed) {
                    throw Positions.syntax(src, base + start, "unterminated string literal");
                }
                add(T.STRING, s.substring(start, i), base + start, sb.toString());
            } else if (c == '=' || c == '!') {
                if (i + 1 < n && s.charAt(i + 1) == '=') {
                    i += 2;
                    add(T.OP, c + "=", base + start, null);
                } else {
                    throw Positions.syntax(src, base + i, "unexpected '" + c + "' (did you mean '" + c + "='?)");
                }
            } else if (c == '<' || c == '>') {
                i++;
                if (i < n && s.charAt(i) == '=') {
                    i++;
                }
                add(T.OP, s.substring(start, i), base + start, null);
            } else if (c == '(') {
                i++;
                add(T.LPAREN, "(", base + start, null);
            } else if (c == ')') {
                i++;
                add(T.RPAREN, ")", base + start, null);
            } else if (c == '|') {
                i++;
                add(T.PIPE, "|", base + start, null);
            } else if (c == ',') {
                i++;
                add(T.COMMA, ",", base + start, null);
            } else {
                throw Positions.syntax(src, base + i, "unexpected character '" + c + "'");
            }
            lastEnd = base + i;
        }
        toks.add(new Tok(T.EOF, "", lastEnd, null));
    }

    private void add(T type, String text, int pos, Object value) {
        toks.add(new Tok(type, text, pos, value));
    }

    private int scanIdent(String s, int i, int base) {
        int n = s.length();
        while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        while (i < n && s.charAt(i) == '.') {
            int j = i + 1;
            if (j >= n || !(Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                throw Positions.syntax(src, base + i, "expected a name after '.'");
            }
            while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                j++;
            }
            i = j;
        }
        return i;
    }

    // ---- parser -----------------------------------------------------------------

    private Tok peek() {
        return toks.get(p);
    }

    private Tok next() {
        Tok t = toks.get(p);
        if (t.type != T.EOF) {
            p++;
        }
        return t;
    }

    private boolean isWord(String w) {
        Tok t = peek();
        return t.type == T.IDENT && t.text.equals(w);
    }

    private TemplateSyntaxException unexpected(Tok t) {
        String what = t.type == T.EOF ? "unexpected end of expression" : "unexpected '" + t.text + "'";
        return Positions.syntax(src, t.pos, what);
    }

    /** Fails unless every token has been consumed. */
    void expectEnd() {
        if (peek().type != T.EOF) {
            throw unexpected(peek());
        }
    }

    /** Parses a full condition (an expression followed by the end of input). */
    Expr parseCondition() {
        Expr e = parseOr();
        expectEnd();
        return e;
    }

    /** Parses {@code expr | filter ...} up to the end of input. */
    Node.Output parseOutput() {
        Expr e = parseOr();
        List<Node.FilterCall> filters = new ArrayList<>();
        while (peek().type == T.PIPE) {
            next();
            Tok name = peek();
            if (name.type != T.IDENT || name.text.indexOf('.') >= 0) {
                throw unexpected(name);
            }
            next();
            List<Object> args = new ArrayList<>();
            if (peek().type == T.LPAREN) {
                next();
                if (peek().type != T.RPAREN) {
                    args.add(literalArg());
                    while (peek().type == T.COMMA) {
                        next();
                        args.add(literalArg());
                    }
                }
                if (peek().type != T.RPAREN) {
                    throw unexpected(peek());
                }
                next();
            }
            Renderer.checkFilter(src, name.text, args.size(), name.pos);
            filters.add(new Node.FilterCall(name.text, List.copyOf(args), name.pos));
        }
        expectEnd();
        return new Node.Output(e, List.copyOf(filters));
    }

    private Object literalArg() {
        Tok t = peek();
        if (t.type == T.STRING || t.type == T.NUMBER) {
            next();
            return t.value;
        }
        throw unexpected(t);
    }

    /** Parses {@code variable in expr}; returns the variable name and expression. */
    record ForHeader(String variable, Expr source, int sourceOffset) {
    }

    ForHeader parseForHeader() {
        Tok v = peek();
        if (v.type != T.IDENT || v.text.indexOf('.') >= 0 || RESERVED.contains(v.text)) {
            throw unexpected(v);
        }
        next();
        if (!isWord("in")) {
            throw unexpected(peek());
        }
        next();
        int sourceOffset = peek().pos;
        Expr e = parseOr();
        expectEnd();
        return new ForHeader(v.text, e, sourceOffset);
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (isWord("or")) {
            next();
            left = new Expr.Or(left, parseAnd());
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseNot();
        while (isWord("and")) {
            next();
            left = new Expr.And(left, parseNot());
        }
        return left;
    }

    private Expr parseNot() {
        if (isWord("not")) {
            Tok t = next();
            enter(t);
            try {
                return new Expr.Not(parseNot());
            } finally {
                depth--;
            }
        }
        return parseCmp();
    }

    private Expr parseCmp() {
        Expr left = parsePrimary();
        if (peek().type == T.OP) {
            Tok op = next();
            Expr right = parsePrimary();
            return new Expr.Cmp(op.text, left, right, op.pos);
        }
        return left;
    }

    private void enter(Tok at) {
        if (++depth > MAX_DEPTH) {
            throw Positions.syntax(src, at.pos, "expression nested too deeply");
        }
    }

    private Expr parsePrimary() {
        Tok t = peek();
        switch (t.type) {
            case LPAREN -> {
                next();
                enter(t);
                Expr e = parseOr();
                depth--;
                if (peek().type != T.RPAREN) {
                    throw unexpected(peek());
                }
                next();
                return e;
            }
            case STRING, NUMBER -> {
                next();
                return new Expr.Lit(t.value);
            }
            case IDENT -> {
                if (RESERVED.contains(t.text)) {
                    next();
                    return switch (t.text) {
                        case "true" -> new Expr.Lit(Boolean.TRUE);
                        case "false" -> new Expr.Lit(Boolean.FALSE);
                        case "null" -> new Expr.Lit(null);
                        default -> throw unexpected(t);
                    };
                }
                next();
                return new Expr.Path(Arrays.asList(t.text.split("\\.")), t.pos);
            }
            default -> throw unexpected(t);
        }
    }
}
