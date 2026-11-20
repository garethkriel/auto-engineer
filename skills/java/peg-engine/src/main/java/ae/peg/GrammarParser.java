package ae.peg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recursive-descent reader for the grammar notation (see TASK.md). */
final class GrammarParser {
    private final String s;
    private int p;
    private final Map<String, Expr> rules = new LinkedHashMap<>();

    private GrammarParser(String s) {
        this.s = s;
    }

    static Map<String, Expr> parseRules(String text) {
        GrammarParser gp = new GrammarParser(text);
        gp.grammar();
        return gp.rules;
    }

    private void grammar() {
        while (true) {
            skip();
            if (p >= s.length()) {
                break;
            }
            if (!identStart(peek())) {
                throw error("expected rule name");
            }
            String name = ident();
            skip();
            if (!s.startsWith("<-", p)) {
                throw error("expected '<-' after rule name '" + name + "'");
            }
            p += 2;
            Expr e = choice();
            if (rules.putIfAbsent(name, e) != null) {
                throw error("duplicate rule '" + name + "'");
            }
        }
        if (rules.isEmpty()) {
            throw new GrammarException("grammar has no rules");
        }
    }

    private Expr choice() {
        List<Expr> alts = new ArrayList<>();
        alts.add(sequence());
        while (true) {
            skip();
            if (p < s.length() && peek() == '/') {
                p++;
                alts.add(sequence());
            } else {
                return Expr.choice(alts);
            }
        }
    }

    private Expr sequence() {
        List<Expr> items = new ArrayList<>();
        while (startsItem()) {
            items.add(prefix());
        }
        if (items.isEmpty()) {
            throw error("expected expression");
        }
        return Expr.seq(items);
    }

    private boolean startsItem() {
        skip();
        if (p >= s.length()) {
            return false;
        }
        char c = peek();
        if ("'\"[.(&!".indexOf(c) >= 0) {
            return true;
        }
        return identStart(c) && !ruleStartAhead();
    }

    private boolean ruleStartAhead() {
        int save = p;
        ident();
        skip();
        boolean arrow = s.startsWith("<-", p);
        p = save;
        return arrow;
    }

    private Expr prefix() {
        skip();
        char c = peek();
        if (c == '&' || c == '!') {
            p++;
            return new Expr.Pred(prefix(), c == '!');
        }
        Expr e = primary();
        while (true) {
            skip();
            if (p >= s.length()) {
                return e;
            }
            char q = peek();
            if (q == '*') {
                e = new Expr.Repeat(e, false);
            } else if (q == '+') {
                e = new Expr.Repeat(e, true);
            } else if (q == '?') {
                e = new Expr.Opt(e);
            } else {
                return e;
            }
            p++;
        }
    }

    private Expr primary() {
        skip();
        char c = peek();
        if (c == '(') {
            p++;
            Expr e = choice();
            skip();
            if (p >= s.length() || peek() != ')') {
                throw error("expected ')'");
            }
            p++;
            return e;
        }
        if (c == '\'' || c == '"') {
            return literal(c);
        }
        if (c == '[') {
            return charClass();
        }
        if (c == '.') {
            p++;
            return new Expr.Any();
        }
        if (identStart(c)) {
            return new Expr.Ref(ident());
        }
        throw error("unexpected '" + c + "'");
    }

    private Expr literal(char quote) {
        p++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (p >= s.length()) {
                throw error("unterminated literal");
            }
            char c = s.charAt(p++);
            if (c == quote) {
                return new Expr.Lit(sb.toString());
            }
            sb.append(c == '\\' ? escape() : c);
        }
    }

    private char escape() {
        if (p >= s.length()) {
            throw error("dangling backslash");
        }
        char c = s.charAt(p++);
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '\\', '\'', '"', ']', '[', '^', '-' -> c;
            default -> throw error("unknown escape '\\" + c + "'");
        };
    }

    private Expr charClass() {
        p++;
        boolean negated = false;
        if (p < s.length() && peek() == '^') {
            negated = true;
            p++;
        }
        List<Expr.Range> ranges = new ArrayList<>();
        while (true) {
            if (p >= s.length()) {
                throw error("unterminated character class");
            }
            if (peek() == ']') {
                p++;
                break;
            }
            char lo = classChar();
            char hi = lo;
            if (p + 1 < s.length() && peek() == '-' && s.charAt(p + 1) != ']') {
                p++;
                hi = classChar();
            }
            if (lo > hi) {
                throw error("empty range '" + lo + "-" + hi + "'");
            }
            ranges.add(new Expr.Range(lo, hi));
        }
        if (ranges.isEmpty()) {
            throw error("empty character class");
        }
        return new Expr.CharClass(negated, ranges);
    }

    private char classChar() {
        if (p >= s.length()) {
            throw error("unterminated character class");
        }
        char c = s.charAt(p++);
        return c == '\\' ? escape() : c;
    }

    private String ident() {
        int start = p;
        while (p < s.length() && (identStart(peek()) || Character.isDigit(peek()))) {
            p++;
        }
        return s.substring(start, p);
    }

    private static boolean identStart(char c) {
        return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private char peek() {
        return s.charAt(p);
    }

    private void skip() {
        while (p < s.length()) {
            char c = peek();
            if (c == '#') {
                while (p < s.length() && peek() != '\n') {
                    p++;
                }
            } else if (Character.isWhitespace(c)) {
                p++;
            } else {
                return;
            }
        }
    }

    private GrammarException error(String msg) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < Math.min(p, s.length()); i++) {
            if (s.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new GrammarException(msg + " at line " + line + ", column " + col);
    }
}
