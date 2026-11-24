package ae.prolog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tokenizer and operator-precedence parser for the supported Prolog subset (see {@link Ops}). */
public final class Parser {
    /** A parsed query: the goal and its named variables in order of first appearance. */
    public record Query(Term goal, Map<String, Var> variables) {}

    private enum Kind { ATOM, QATOM, VAR, INT, PUNCT, END, EOF }

    private record Token(Kind kind, String text, long number, int line, int col, boolean layoutBefore) {}

    private final List<Token> tokens;
    private int pos;
    private Map<String, Var> vars = new LinkedHashMap<>();

    private Parser(String text) {
        this.tokens = new Lexer(text).run();
    }

    // ------------------------------------------------------------------ entry points

    /** Parses one term; a trailing {@code .} is optional. */
    public static Term parseTerm(String text) {
        return parseQuery(text).goal();
    }

    public static Query parseQuery(String text) {
        Parser p = new Parser(text);
        Term t = p.parse(1200);
        if (p.peek().kind == Kind.END) p.pos++;
        if (p.peek().kind != Kind.EOF) throw p.error(p.peek(), "unexpected '" + p.peek().text + "'");
        return new Query(t, p.vars);
    }

    public static List<Clause> parseProgram(String text) {
        Parser p = new Parser(text);
        List<Clause> out = new ArrayList<>();
        while (p.peek().kind != Kind.EOF) {
            p.vars = new LinkedHashMap<>();
            Term t = p.parse(1200);
            if (p.peek().kind != Kind.END) throw p.error(p.peek(), "expected '.' but found '" + p.peek().text + "'");
            p.pos++;
            if (t instanceof Struct s && s.name().equals(":-") && s.arity() == 2) {
                out.add(new Clause(s.arg(0), s.arg(1)));
            } else {
                out.add(new Clause(t, new Atom("true")));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ parser

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekAt(int offset) {
        return tokens.get(Math.min(pos + offset, tokens.size() - 1));
    }

    private PrologError error(Token t, String msg) {
        return new PrologError(PrologError.Kind.SYNTAX, "line " + t.line + ", column " + t.col + ": " + msg);
    }

    private boolean isPunct(Token t, String s) {
        return t.kind == Kind.PUNCT && t.text.equals(s);
    }

    private void expect(String punct) {
        Token t = peek();
        if (!isPunct(t, punct)) throw error(t, "expected '" + punct + "' but found " + describe(t));
        pos++;
    }

    private static String describe(Token t) {
        return t.kind == Kind.EOF ? "end of input" : "'" + t.text + "'";
    }

    private record Parsed(Term term, int prec) {}

    private Term parse(int max) {
        Parsed left = primary(max);
        Term t = left.term;
        int lp = left.prec;
        while (true) {
            Token tok = peek();
            String name = null;
            if (isPunct(tok, ",")) name = ",";
            else if (tok.kind == Kind.ATOM) name = tok.text;
            Ops.Op op = name == null ? null : Ops.INFIX.get(name);
            if (op == null || op.prec() > max || lp > op.leftMax()) return t;
            pos++;
            Term right = parse(op.rightMax());
            t = new Struct(name, t, right);
            lp = op.prec();
        }
    }

    private boolean startsTerm(Token t) {
        switch (t.kind) {
            case END: case EOF: return false;
            case PUNCT: return t.text.equals("(") || t.text.equals("[");
            case ATOM: return !Ops.INFIX.containsKey(t.text) || Ops.PREFIX.containsKey(t.text);
            default: return true;
        }
    }

    private Parsed primary(int max) {
        Token t = peek();
        pos++;
        switch (t.kind) {
            case INT:
                if (t.number == Long.MIN_VALUE) throw error(t, "integer too large: " + t.text);
                return new Parsed(new Int(t.number), 0);
            case VAR:
                if (t.text.equals("_")) return new Parsed(new Var("_"), 0);
                return new Parsed(vars.computeIfAbsent(t.text, Var::new), 0);
            case PUNCT:
                if (t.text.equals("(")) {
                    Term inner = parse(1200);
                    expect(")");
                    return new Parsed(inner, 0);
                }
                if (t.text.equals("[")) return new Parsed(list(), 0);
                throw error(t, "unexpected " + describe(t));
            case ATOM:
            case QATOM:
                return atomOrCompound(t, max);
            default:
                throw error(t, "unexpected " + describe(t));
        }
    }

    private Parsed atomOrCompound(Token t, int max) {
        Token next = peek();
        if (isPunct(next, "(") && !next.layoutBefore) {
            pos++;
            List<Term> args = new ArrayList<>();
            args.add(parse(999));
            while (isPunct(peek(), ",")) {
                pos++;
                args.add(parse(999));
            }
            expect(")");
            return new Parsed(new Struct(t.text, args.toArray(new Term[0])), 0);
        }
        if (t.kind == Kind.ATOM && t.text.equals("-") && next.kind == Kind.INT && !next.layoutBefore) {
            pos++;
            return new Parsed(new Int(-next.number), 0);
        }
        if (t.kind == Kind.ATOM && Ops.PREFIX.containsKey(t.text) && startsTerm(next)) {
            int p = Ops.PREFIX.get(t.text);
            int argMax = Math.min(p, max);
            Term arg = parse(argMax);
            return new Parsed(new Struct(t.text, arg), Math.min(p, max));
        }
        return new Parsed(new Atom(t.text), 0);
    }

    private Term list() {
        if (isPunct(peek(), "]")) {
            pos++;
            return Terms.NIL;
        }
        List<Term> items = new ArrayList<>();
        items.add(parse(999));
        while (isPunct(peek(), ",")) {
            pos++;
            items.add(parse(999));
        }
        Term tail = Terms.NIL;
        if (isPunct(peek(), "|")) {
            pos++;
            tail = parse(999);
        }
        expect("]");
        return Terms.list(items, tail);
    }

    // ------------------------------------------------------------------ lexer

    private static final class Lexer {
        private final String s;
        private int i;
        private int line = 1;
        private int col = 1;

        Lexer(String s) {
            this.s = s;
        }

        private PrologError err(int l, int c, String msg) {
            return new PrologError(PrologError.Kind.SYNTAX, "line " + l + ", column " + c + ": " + msg);
        }

        private char cur() {
            return s.charAt(i);
        }

        private void advance() {
            if (s.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            i++;
        }

        List<Token> run() {
            List<Token> out = new ArrayList<>();
            while (true) {
                boolean layout = skipLayout();
                if (i >= s.length()) {
                    out.add(new Token(Kind.EOF, "", 0, line, col, layout));
                    return out;
                }
                out.add(next(layout));
            }
        }

        private boolean skipLayout() {
            boolean any = false;
            while (i < s.length()) {
                char c = cur();
                if (Character.isWhitespace(c)) {
                    advance();
                    any = true;
                } else if (c == '%') {
                    while (i < s.length() && cur() != '\n') advance();
                    any = true;
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    int l = line, cc = col;
                    advance();
                    advance();
                    while (true) {
                        if (i + 1 >= s.length()) throw err(l, cc, "unterminated comment");
                        if (cur() == '*' && s.charAt(i + 1) == '/') break;
                        advance();
                    }
                    advance();
                    advance();
                    any = true;
                } else {
                    break;
                }
            }
            return any;
        }

        private Token next(boolean layout) {
            int l = line, c = col;
            char ch = cur();
            if (Character.isDigit(ch)) {
                int start = i;
                while (i < s.length() && Character.isDigit(cur())) advance();
                String digits = s.substring(start, i);
                try {
                    return new Token(Kind.INT, digits, Long.parseLong(digits), l, c, layout);
                } catch (NumberFormatException e) {
                    // 2^63 is only legal as the operand of a negative literal; the parser checks the sign
                    if (digits.equals("9223372036854775808")) return new Token(Kind.INT, digits, Long.MIN_VALUE, l, c, layout);
                    throw err(l, c, "integer too large: " + digits);
                }
            }
            if (ch == '_' || Character.isUpperCase(ch)) {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(cur()) || cur() == '_')) advance();
                return new Token(Kind.VAR, s.substring(start, i), 0, l, c, layout);
            }
            if (Character.isLowerCase(ch)) {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(cur()) || cur() == '_')) advance();
                return new Token(Kind.ATOM, s.substring(start, i), 0, l, c, layout);
            }
            if (ch == '\'') {
                advance();
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (i >= s.length()) throw err(l, c, "unterminated quoted atom");
                    char d = cur();
                    if (d == '\'') {
                        advance();
                        if (i < s.length() && cur() == '\'') {
                            sb.append('\'');
                            advance();
                            continue;
                        }
                        break;
                    }
                    sb.append(d);
                    advance();
                }
                return new Token(Kind.QATOM, sb.toString(), 0, l, c, layout);
            }
            if ("()[],|".indexOf(ch) >= 0) {
                advance();
                return new Token(Kind.PUNCT, String.valueOf(ch), 0, l, c, layout);
            }
            if (ch == '!' || ch == ';') {
                advance();
                return new Token(Kind.ATOM, String.valueOf(ch), 0, l, c, layout);
            }
            if (Ops.isSymbolChar(ch)) {
                int start = i;
                while (i < s.length() && Ops.isSymbolChar(cur())) advance();
                String run = s.substring(start, i);
                boolean endsClause = run.equals(".") && (i >= s.length() || Character.isWhitespace(cur()) || cur() == '%');
                return new Token(endsClause ? Kind.END : Kind.ATOM, run, 0, l, c, layout);
            }
            throw err(l, c, "unexpected character '" + ch + "'");
        }
    }
}
