package glob;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiled path glob. Supports {@code ?}, {@code *}, {@code **}, {@code **}{@code /}, character
 * classes {@code [a-z]} / {@code [!abc]} and backslash escapes. Matching is memoised, so it is
 * O(tokens * length) even for pathological patterns.
 */
public final class Glob {
    private enum Kind { LITERAL, ANY_CHAR, STAR, DOUBLE_STAR, DIRS, CLASS }

    private record Token(Kind kind, char ch, boolean negated, char[] lo, char[] hi) {
        static Token of(Kind kind) {
            return new Token(kind, '\0', false, null, null);
        }

        boolean matchesChar(char c) {
            return switch (kind) {
                case LITERAL -> c == ch;
                case ANY_CHAR -> c != '/';
                case CLASS -> c != '/' && inClass(c) != negated;
                default -> throw new IllegalStateException(kind.name());
            };
        }

        private boolean inClass(char c) {
            for (int i = 0; i < lo.length; i++) {
                if (c >= lo[i] && c <= hi[i]) return true;
            }
            return false;
        }
    }

    private final String pattern;
    private final List<Token> tokens;

    private Glob(String pattern, List<Token> tokens) {
        this.pattern = pattern;
        this.tokens = tokens;
    }

    /** Parses {@code pattern}; throws {@link IllegalArgumentException} if it is malformed. */
    public static Glob compile(String pattern) {
        if (pattern == null) throw new IllegalArgumentException("pattern is null");
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = pattern.length();
        while (i < n) {
            char c = pattern.charAt(i);
            boolean segStart = i == 0 || pattern.charAt(i - 1) == '/';
            if (c == '\\') {
                if (i + 1 >= n) throw new IllegalArgumentException("trailing backslash in " + pattern);
                out.add(new Token(Kind.LITERAL, pattern.charAt(i + 1), false, null, null));
                i += 2;
            } else if (c == '?') {
                out.add(Token.of(Kind.ANY_CHAR));
                i++;
            } else if (c == '*') {
                int j = i;
                while (j < n && pattern.charAt(j) == '*') j++;
                if (j - i >= 2) {
                    if (segStart && j < n && pattern.charAt(j) == '/') {
                        out.add(Token.of(Kind.DIRS));
                        i = j + 1;
                    } else {
                        out.add(Token.of(Kind.DOUBLE_STAR));
                        i = j;
                    }
                } else {
                    out.add(Token.of(Kind.STAR));
                    i = j;
                }
            } else if (c == '[') {
                i = parseClass(pattern, i, out);
            } else {
                out.add(new Token(Kind.LITERAL, c, false, null, null));
                i++;
            }
        }
        return new Glob(pattern, List.copyOf(out));
    }

    private static int parseClass(String p, int open, List<Token> out) {
        int n = p.length();
        int i = open + 1;
        boolean negated = false;
        if (i < n && p.charAt(i) == '!') {
            negated = true;
            i++;
        }
        List<Character> lo = new ArrayList<>();
        List<Character> hi = new ArrayList<>();
        boolean first = true;
        while (true) {
            if (i >= n) throw new IllegalArgumentException("unclosed '[' in " + p);
            char c = p.charAt(i);
            if (c == ']' && !first) {
                i++;
                break;
            }
            first = false;
            if (c == '\\') {
                if (i + 1 >= n) throw new IllegalArgumentException("unclosed '[' in " + p);
                c = p.charAt(++i);
            }
            i++;
            if (i + 1 < n && p.charAt(i) == '-' && p.charAt(i + 1) != ']') {
                char end = p.charAt(i + 1);
                int consumed = 2;
                if (end == '\\') {
                    if (i + 2 >= n) throw new IllegalArgumentException("unclosed '[' in " + p);
                    end = p.charAt(i + 2);
                    consumed = 3;
                }
                if (end < c) throw new IllegalArgumentException("reversed range in " + p);
                lo.add(c);
                hi.add(end);
                i += consumed;
            } else {
                lo.add(c);
                hi.add(c);
            }
        }
        if (lo.isEmpty()) throw new IllegalArgumentException("empty class in " + p);
        char[] l = new char[lo.size()];
        char[] h = new char[lo.size()];
        for (int k = 0; k < l.length; k++) {
            l[k] = lo.get(k);
            h[k] = hi.get(k);
        }
        out.add(new Token(Kind.CLASS, '\0', negated, l, h));
        return i;
    }

    /** True if the whole {@code path} matches. */
    public boolean matches(String path) {
        Objects.requireNonNull(path, "path");
        // memo[t][p]: 0 unknown, 1 true, 2 false
        byte[][] memo = new byte[tokens.size() + 1][path.length() + 1];
        return match(0, 0, path, memo);
    }

    private boolean match(int t, int p, String s, byte[][] memo) {
        if (memo[t][p] != 0) return memo[t][p] == 1;
        boolean r = compute(t, p, s, memo);
        memo[t][p] = (byte) (r ? 1 : 2);
        return r;
    }

    private boolean compute(int t, int p, String s, byte[][] memo) {
        if (t == tokens.size()) return p == s.length();
        Token tok = tokens.get(t);
        switch (tok.kind()) {
            case STAR:
                for (int q = p; ; q++) {
                    if (match(t + 1, q, s, memo)) return true;
                    if (q >= s.length() || s.charAt(q) == '/') return false;
                }
            case DOUBLE_STAR:
                for (int q = p; q <= s.length(); q++) {
                    if (match(t + 1, q, s, memo)) return true;
                }
                return false;
            case DIRS:
                // zero directories, or consume "segment/" repeatedly
                for (int q = p; ; ) {
                    if (match(t + 1, q, s, memo)) return true;
                    int slash = s.indexOf('/', q);
                    if (slash < 0) return false;
                    q = slash + 1;
                }
            default:
                return p < s.length() && tok.matchesChar(s.charAt(p)) && match(t + 1, p + 1, s, memo);
        }
    }

    /** The source pattern. */
    public String pattern() {
        return pattern;
    }

    @Override
    public String toString() {
        return "Glob[" + pattern + "]";
    }
}
