package bench.regex;

import java.util.ArrayList;
import java.util.List;

/** Linear-time (Thompson NFA simulation) regular expressions. */
public final class Regex {
    private static final int CHAR = 0;
    private static final int ANY = 1;
    private static final int CLASS = 2;
    private static final int SPLIT = 3;
    private static final int BOL = 4;
    private static final int EOL = 5;
    private static final int MATCH = 6;

    // ---- AST ----
    private sealed interface Node permits Lit, Any, Cls, Anchor, Cat, Alt, Rep {}
    private record Lit(char c) implements Node {}
    private record Any() implements Node {}
    private record Cls(boolean negate, char[] lo, char[] hi) implements Node {}
    private record Anchor(boolean start) implements Node {}
    private record Cat(List<Node> parts) implements Node {}
    private record Alt(List<Node> options) implements Node {}
    /** kind: '*', '+', '?'. */
    private record Rep(Node child, char kind) implements Node {}

    // ---- parser ----
    private static final class Parser {
        final String p;
        int pos;

        Parser(String p) {
            this.p = p;
        }

        Node parse() {
            Node n = alt(0);
            if (pos < p.length()) {
                throw new IllegalArgumentException("unbalanced ')' at " + pos);
            }
            return n;
        }

        Node alt(int depth) {
            List<Node> options = new ArrayList<>();
            options.add(concat(depth));
            while (pos < p.length() && p.charAt(pos) == '|') {
                pos++;
                options.add(concat(depth));
            }
            return options.size() == 1 ? options.get(0) : new Alt(options);
        }

        Node concat(int depth) {
            List<Node> parts = new ArrayList<>();
            while (pos < p.length()) {
                char c = p.charAt(pos);
                if (c == '|') {
                    break;
                }
                if (c == ')') {
                    if (depth == 0) {
                        throw new IllegalArgumentException("unbalanced ')' at " + pos);
                    }
                    break;
                }
                if (c == '*' || c == '+' || c == '?') {
                    throw new IllegalArgumentException("quantifier without atom at " + pos);
                }
                Node atom = atom(depth);
                if (pos < p.length()) {
                    char q = p.charAt(pos);
                    if (q == '*' || q == '+' || q == '?') {
                        pos++;
                        atom = new Rep(atom, q);
                        if (pos < p.length()) {
                            char q2 = p.charAt(pos);
                            if (q2 == '*' || q2 == '+' || q2 == '?') {
                                throw new IllegalArgumentException("double quantifier at " + pos);
                            }
                        }
                    }
                }
                parts.add(atom);
            }
            return parts.size() == 1 ? parts.get(0) : new Cat(parts);
        }

        Node atom(int depth) {
            char c = p.charAt(pos++);
            switch (c) {
                case '(': {
                    Node inner = alt(depth + 1);
                    if (pos >= p.length() || p.charAt(pos) != ')') {
                        throw new IllegalArgumentException("unbalanced '('");
                    }
                    pos++;
                    return inner;
                }
                case '[':
                    return cls();
                case ']':
                    throw new IllegalArgumentException("unmatched ']'");
                case '.':
                    return new Any();
                case '^':
                    return new Anchor(true);
                case '$':
                    return new Anchor(false);
                case '\\':
                    if (pos >= p.length()) {
                        throw new IllegalArgumentException("trailing backslash");
                    }
                    return new Lit(p.charAt(pos++));
                default:
                    return new Lit(c);
            }
        }

        char classChar() {
            if (pos >= p.length()) {
                throw new IllegalArgumentException("unterminated class");
            }
            char c = p.charAt(pos++);
            if (c == '\\') {
                if (pos >= p.length()) {
                    throw new IllegalArgumentException("unterminated class");
                }
                c = p.charAt(pos++);
            }
            return c;
        }

        Node cls() {
            boolean negate = false;
            if (pos < p.length() && p.charAt(pos) == '^') {
                negate = true;
                pos++;
            }
            List<char[]> ranges = new ArrayList<>();
            while (true) {
                if (pos >= p.length()) {
                    throw new IllegalArgumentException("unterminated class");
                }
                if (p.charAt(pos) == ']') {
                    pos++;
                    break;
                }
                char lo = classChar();
                char hi = lo;
                if (pos + 1 < p.length() && p.charAt(pos) == '-' && p.charAt(pos + 1) != ']') {
                    pos++;
                    hi = classChar();
                    if (hi < lo) {
                        throw new IllegalArgumentException("reversed range");
                    }
                }
                ranges.add(new char[] {lo, hi});
            }
            if (ranges.isEmpty()) {
                throw new IllegalArgumentException("empty class");
            }
            char[] los = new char[ranges.size()];
            char[] his = new char[ranges.size()];
            for (int i = 0; i < los.length; i++) {
                los[i] = ranges.get(i)[0];
                his[i] = ranges.get(i)[1];
            }
            return new Cls(negate, los, his);
        }
    }

    // ---- NFA ----
    private int[] type = new int[16];
    private int[] out1 = new int[16];
    private int[] out2 = new int[16];
    private int[] arg = new int[16];
    private final List<Cls> classes = new ArrayList<>();
    private int count;
    private int start;

    private int newState(int t, int a, int o1, int o2) {
        if (count == type.length) {
            int len = count * 2;
            type = java.util.Arrays.copyOf(type, len);
            out1 = java.util.Arrays.copyOf(out1, len);
            out2 = java.util.Arrays.copyOf(out2, len);
            arg = java.util.Arrays.copyOf(arg, len);
        }
        type[count] = t;
        arg[count] = a;
        out1[count] = o1;
        out2[count] = o2;
        return count++;
    }

    /** Builds states for node so that it continues to state {@code next}; returns entry state. */
    private int build(Node n, int next) {
        if (n instanceof Lit l) {
            return newState(CHAR, l.c(), next, -1);
        } else if (n instanceof Any) {
            return newState(ANY, 0, next, -1);
        } else if (n instanceof Cls c) {
            classes.add(c);
            return newState(CLASS, classes.size() - 1, next, -1);
        } else if (n instanceof Anchor a) {
            return newState(a.start() ? BOL : EOL, 0, next, -1);
        } else if (n instanceof Cat cat) {
            int cur = next;
            for (int i = cat.parts().size() - 1; i >= 0; i--) {
                cur = build(cat.parts().get(i), cur);
            }
            return cur;
        } else if (n instanceof Alt alt) {
            int cur = build(alt.options().get(alt.options().size() - 1), next);
            for (int i = alt.options().size() - 2; i >= 0; i--) {
                int o = build(alt.options().get(i), next);
                cur = newState(SPLIT, 0, o, cur);
            }
            return cur;
        } else {
            Rep r = (Rep) n;
            if (r.kind() == '?') {
                int body = build(r.child(), next);
                return newState(SPLIT, 0, body, next);
            }
            int split = newState(SPLIT, 0, -1, next);
            int body = build(r.child(), split);
            out1[split] = body;
            return r.kind() == '*' ? split : body;
        }
    }

    private Regex(String pattern) {
        Node ast = new Parser(pattern).parse();
        int m = newState(MATCH, 0, -1, -1);
        start = build(ast, m);
    }

    public static Regex compile(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("null pattern");
        }
        return new Regex(pattern);
    }

    private boolean consumes(int s, char ch) {
        switch (type[s]) {
            case CHAR:
                return arg[s] == ch;
            case ANY:
                return true;
            case CLASS: {
                Cls c = classes.get(arg[s]);
                boolean in = false;
                for (int i = 0; i < c.lo().length; i++) {
                    if (ch >= c.lo()[i] && ch <= c.hi()[i]) {
                        in = true;
                        break;
                    }
                }
                return in != c.negate();
            }
            default:
                return false;
        }
    }

    /** Simulation state reused across a run. */
    private final class Run {
        final int[] mark = new int[count];
        int gen;
        final int[] stack = new int[count + 1];
        boolean matched;

        /** Adds the epsilon closure of s at input position pos to list; returns new list size. */
        int add(int[] list, int size, int s0, int pos, int n) {
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
                        matched = true;
                        break;
                    default:
                        list[size++] = s;
                }
            }
            return size;
        }
    }

    private boolean run(String input, boolean find) {
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        int n = input.length();
        Run r = new Run();
        int[] cur = new int[count];
        int[] nxt = new int[count];
        r.gen = 1;
        int curSize = r.add(cur, 0, start, 0, n);
        for (int pos = 0; ; pos++) {
            if (r.matched) {
                if (find || pos == n) {
                    return true;
                }
            }
            if (pos == n) {
                return false;
            }
            char ch = input.charAt(pos);
            r.matched = false;
            r.gen++;
            int nxtSize = 0;
            for (int i = 0; i < curSize; i++) {
                int s = cur[i];
                if (consumes(s, ch)) {
                    nxtSize = r.add(nxt, nxtSize, out1[s], pos + 1, n);
                }
            }
            if (find) {
                nxtSize = r.add(nxt, nxtSize, start, pos + 1, n);
            }
            int[] t = cur;
            cur = nxt;
            nxt = t;
            curSize = nxtSize;
            if (!find && curSize == 0 && !r.matched) {
                return false;
            }
        }
    }

    public boolean matches(String input) {
        return run(input, false);
    }

    public boolean find(String input) {
        return run(input, true);
    }
}
