package ae.bdd;

import java.util.List;

/**
 * Parser for propositional formulas that builds a {@link Bdd} directly.
 *
 * <p>Grammar, loosest binding first: {@code <->}, {@code ->} (right associative), {@code |},
 * {@code ^}, {@code &}, prefix {@code !}, atoms (identifier, {@code 0}, {@code 1}, {@code true},
 * {@code false}, parenthesised formula). Errors are {@link IllegalArgumentException}s whose message
 * starts with {@code col N:} (1-based column).
 */
public final class Expr {
    private final Bdd bdd;
    private final String text;
    private final List<String> names;
    private int pos;

    private Expr(Bdd bdd, String text, List<String> names) {
        this.bdd = bdd;
        this.text = text;
        this.names = names;
    }

    /**
     * Parses {@code text}. New identifiers are appended to {@code names} (variable index = list
     * position). On failure {@code names} is restored to its original contents.
     */
    public static int parse(Bdd bdd, String text, List<String> names) {
        int original = names.size();
        Expr p = new Expr(bdd, text, names);
        try {
            int f = p.iff();
            p.skip();
            if (p.pos < text.length()) throw p.unexpected(p.pos);
            return f;
        } catch (RuntimeException e) {
            while (names.size() > original) names.remove(names.size() - 1);
            throw e;
        }
    }

    private IllegalArgumentException error(int at, String what) {
        return new IllegalArgumentException("col " + (at + 1) + ": " + what);
    }

    private String describe(int at) {
        char c = text.charAt(at);
        return "'" + c + "'";
    }

    private void skip() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private boolean accept(String token) {
        skip();
        if (text.startsWith(token, pos)) {
            pos += token.length();
            return true;
        }
        return false;
    }

    private int iff() {
        int left = imp();
        while (accept("<->")) left = bdd.iff(left, imp());
        return left;
    }

    private int imp() {
        int left = or();
        if (accept("->")) return bdd.imp(left, imp());
        return left;
    }

    private int or() {
        int left = xor();
        while (accept("|")) left = bdd.or(left, xor());
        return left;
    }

    private int xor() {
        int left = and();
        while (accept("^")) left = bdd.xor(left, and());
        return left;
    }

    private int and() {
        int left = unary();
        while (accept("&")) left = bdd.and(left, unary());
        return left;
    }

    private int unary() {
        if (accept("!")) return bdd.not(unary());
        return atom();
    }

    private int atom() {
        skip();
        if (pos >= text.length()) throw error(pos, "unexpected end of input");
        char c = text.charAt(pos);
        if (c == '(') {
            pos++;
            int f = iff();
            skip();
            if (pos >= text.length() || text.charAt(pos) != ')') throw error(pos, "expected ')'");
            pos++;
            return f;
        }
        if (Character.isLetter(c) && c < 128 || c == '_') {
            int start = pos;
            while (pos < text.length() && isIdentPart(text.charAt(pos))) pos++;
            String word = text.substring(start, pos);
            if (word.equals("true")) return Bdd.TRUE;
            if (word.equals("false")) return Bdd.FALSE;
            int idx = names.indexOf(word);
            if (idx < 0) {
                if (names.size() >= bdd.numVars()) throw error(start, "too many variables ('" + word + "')");
                names.add(word);
                idx = names.size() - 1;
            }
            return bdd.var(idx);
        }
        if (c == '0' || c == '1') {
            pos++;
            return c == '1' ? Bdd.TRUE : Bdd.FALSE;
        }
        throw unexpected(pos);
    }

    /** Operators and parentheses are "unexpected 'c'"; anything else is an unexpected character. */
    private IllegalArgumentException unexpected(int at) {
        char c = text.charAt(at);
        boolean known = "&|^()<-!".indexOf(c) >= 0 || isIdentPart(c);
        return error(at, (known ? "unexpected " : "unexpected character ") + describe(at));
    }

    private static boolean isIdentPart(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '_');
    }
}
