package bench.regex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Linear-time (Thompson NFA simulation) regular expressions. */
public final class Regex {
    private static final int CHAR = 0, ANY = 1, CLASS = 2, SPLIT = 3, BOL = 4, EOL = 5, MATCH = 6;

    // ---- AST ----
    private sealed interface Node permits Empty, Lit, AnyN, Cls, Anchor, Cat, Alt, Rep {}
    private record Empty() implements Node {}
    private record Lit(char c) implements Node {}
    private record AnyN() implements Node {}
    private record Cls(boolean neg, char[] lo, char[] hi) implements Node {}
    private record Anchor(boolean start) implements Node {}
    private record Cat(List<Node> parts) implements Node {}
    private record Alt(List<Node> alts) implements Node {}
    /** kind: '*', '+', '?'. */
    private record Rep(Node body, char kind) implements Node {}

    // ---- parser ----
    private static final class Parser {
        final String s;
        int p;

        Parser(String s) {
            this.s = s;
        }

        Node parse() {
            Node n = alt();
            if (p < s.length()) {
                // only ')' can stop alt() early
                throw new IllegalArgumentException("unbalanced parenthesis at " + p);
            }
            return n;
        }

        Node alt() {
            List<Node> alts = new ArrayList<>();
            alts.add(seq());
            while (p < s.length() && s.charAt(p) == '|') {
                p++;
                alts.add(seq());
            }
            return alts.size() == 1 ? alts.get(0) : new Alt(alts);
        }

        Node seq() {
            List<Node> parts = new ArrayList<>();
            while (p < s.length()) {
                char c = s.charAt(p);
                if (c == '|' || c == ')') {
                    break;
                }
                if (c == '*' || c == '+' || c == '?') {
                    throw new IllegalArgumentException("quantifier without atom at " + p);
                }
                Node atom = atom();
                if (p < s.length()) {
                    char q = s.charAt(p);
                    if (q == '*' || q == '+' || q == '?') {
                        p++;
                        atom = new Rep(atom, q);
                        if (p < s.length()) {
                            char q2 = s.charAt(p);
                            if (q2 == '*' || q2 == '+' || q2 == '?') {
                                throw new IllegalArgumentException("repeated quantifier at " + p);
                            }
                        }
                    }
                }
                parts.add(atom);
            }
            if (parts.isEmpty()) {
                return new Empty();
            }
            return parts.size() == 1 ? parts.get(0) : new Cat(parts);
        }

        Node atom() {
            char c = s.charAt(p++);
            switch (c) {
                case '(': {
                    Node inner = alt();
                    if (p >= s.length() || s.charAt(p) != ')') {
                        throw new IllegalArgumentException("unterminated group");
                    }
                    p++;
                    return inner;
                }
                case ']':
                    throw new IllegalArgumentException("unmatched ]");
                case '[':
                    return cls();
                case '.':
                    return new AnyN();
                case '^':
                    return new Anchor(true);
                case '$':
                    return new Anchor(false);
                case '\\':
                    if (p >= s.length()) {
                        throw new IllegalArgumentException("trailing backslash");
                    }
                    return new Lit(s.charAt(p++));
                default:
                    return new Lit(c);
            }
        }

        char classChar() {
            if (p >= s.length()) {
                throw new IllegalArgumentException("unterminated class");
            }
            char c = s.charAt(p++);
            if (c == '\\') {
                if (p >= s.length()) {
                    throw new IllegalArgumentException("unterminated class");
                }
                c = s.charAt(p++);
            }
            return c;
        }

        Node cls() {
            boolean neg = false;
            if (p < s.length() && s.charAt(p) == '^') {
                neg = true;
                p++;
            }
            StringBuilder lo = new StringBuilder();
            StringBuilder hi = new StringBuilder();
            while (true) {
                if (p >= s.length()) {
                    throw new IllegalArgumentException("unterminated class");
                }
                if (s.charAt(p) == ']') {
                    p++;
                    break;
                }
                char a = classChar();
                char b = a;
                if (p + 1 < s.length() && s.charAt(p) == '-' && s.charAt(p + 1) != ']') {
                    p++;
                    b = classChar();
                    if (b < a) {
                        throw new IllegalArgumentException("reversed range");
                    }
                }
                lo.append(a);
                hi.append(b);
            }
            if (lo.length() == 0) {
                throw new IllegalArgumentException("empty class");
            }
            return new Cls(neg, lo.toString().toCharArray(), hi.toString().toCharArray());
        }
    }

    // ---- NFA ----
    private int[] type = new int[16];
    private int[] out1 = new int[16];
    private int[] out2 = new int[16];
    private char[] ch = new char[16];
    private Cls[] cls = new Cls[16];
    private int count;
    private int start;
    private int matchState;

    private int newState(int t, int o1, int o2, char c, Cls k) {
        if (count == type.length) {
            int n = count * 2;
            type = Arrays.copyOf(type, n);
            out1 = Arrays.copyOf(out1, n);
            out2 = Arrays.copyOf(out2, n);
            ch = Arrays.copyOf(ch, n);
            cls = Arrays.copyOf(cls, n);
        }
        type[count] = t;
        out1[count] = o1;
        out2[count] = o2;
        ch[count] = c;
        cls[count] = k;
        return count++;
    }

    /** Builds states for node so that it continues to {@code next}; returns its entry state. */
    private int build(Node node, int next) {
        if (node instanceof Empty) {
            return next;
        } else if (node instanceof Lit l) {
            return newState(CHAR, next, -1, l.c(), null);
        } else if (node instanceof AnyN) {
            return newState(ANY, next, -1, (char) 0, null);
        } else if (node instanceof Cls k) {
            return newState(CLASS, next, -1, (char) 0, k);
        } else if (node instanceof Anchor a) {
            return newState(a.start() ? BOL : EOL, next, -1, (char) 0, null);
        } else if (node instanceof Cat c) {
            int cur = next;
            for (int i = c.parts().size() - 1; i >= 0; i--) {
                cur = build(c.parts().get(i), cur);
            }
            return cur;
        } else if (node instanceof Alt a) {
            int cur = build(a.alts().get(a.alts().size() - 1), next);
            for (int i = a.alts().size() - 2; i >= 0; i--) {
                int b = build(a.alts().get(i), next);
                cur = newState(SPLIT, b, cur, (char) 0, null);
            }
            return cur;
        } else {
            Rep r = (Rep) node;
            if (r.kind() == '?') {
                int b = build(r.body(), next);
                return newState(SPLIT, b, next, (char) 0, null);
            }
            int split = newState(SPLIT, -1, next, (char) 0, null);
            int b = build(r.body(), split);
            out1[split] = b;
            return r.kind() == '*' ? split : b;
        }
    }

    private Regex(String pattern) {
        Node ast = new Parser(pattern).parse();
        matchState = newState(MATCH, -1, -1, (char) 0, null);
        start = build(ast, matchState);
    }

    public static Regex compile(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("null pattern");
        }
        return new Regex(pattern);
    }

    public boolean matches(String input) {
        return run(input, false);
    }

    public boolean find(String input) {
        return run(input, true);
    }

    private boolean charMatches(int st, char c) {
        switch (type[st]) {
            case CHAR:
                return ch[st] == c;
            case ANY:
                return true;
            default: {
                Cls k = cls[st];
                boolean in = false;
                for (int i = 0; i < k.lo().length; i++) {
                    if (c >= k.lo()[i] && c <= k.hi()[i]) {
                        in = true;
                        break;
                    }
                }
                return in != k.neg();
            }
        }
    }

    private boolean run(String input, boolean find) {
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        int n = input.length();
        int[] mark = new int[count];
        int[] stack = new int[count * 2 + 2];
        int[] cur = new int[count];
        int[] nxt = new int[count];
        int curN = 0;
        int gen = 1;
        boolean[] matched = new boolean[1];

        curN = closure(start, 0, n, mark, gen, stack, cur, curN, matched);
        for (int i = 0; ; i++) {
            if (find && matched[0]) {
                return true;
            }
            if (i == n) {
                return matched[0];
            }
            char c = input.charAt(i);
            gen++;
            matched[0] = false;
            int nxtN = 0;
            for (int k = 0; k < curN; k++) {
                int st = cur[k];
                if (charMatches(st, c)) {
                    nxtN = closure(out1[st], i + 1, n, mark, gen, stack, nxt, nxtN, matched);
                }
            }
            if (find) {
                nxtN = closure(start, i + 1, n, mark, gen, stack, nxt, nxtN, matched);
            }
            int[] tmp = cur;
            cur = nxt;
            nxt = tmp;
            curN = nxtN;
        }
    }

    private int closure(int s0, int pos, int n, int[] mark, int gen, int[] stack, int[] list, int listN,
                        boolean[] matched) {
        int sp = 0;
        stack[sp++] = s0;
        while (sp > 0) {
            int s = stack[--sp];
            if (mark[s] == gen) {
                continue;
            }
            mark[s] = gen;
            switch (type[s]) {
                case SPLIT:
                    stack[sp++] = out2[s];
                    stack[sp++] = out1[s];
                    break;
                case BOL:
                    if (pos == 0) {
                        stack[sp++] = out1[s];
                    }
                    break;
                case EOL:
                    if (pos == n) {
                        stack[sp++] = out1[s];
                    }
                    break;
                case MATCH:
                    matched[0] = true;
                    break;
                default:
                    list[listN++] = s;
            }
        }
        return listN;
    }
}
