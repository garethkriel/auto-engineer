package bench.regex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Linear-time (Thompson NFA simulation) regular expressions. */
public final class Regex {
    private static final int CHAR = 0, ANY = 1, CLASS = 2, SPLIT = 3, BOL = 4, EOL = 5, MATCH = 6;

    // ---- AST ----
    private sealed interface Node permits Lit, Any, Cls, Cat, Alt, Rep, Bol, Eol {}
    private record Lit(char c) implements Node {}
    private record Any() implements Node {}
    private record Cls(boolean neg, int[] ranges) implements Node {}
    private record Cat(List<Node> items) implements Node {}
    private record Alt(List<Node> alts) implements Node {}
    private record Rep(Node child, char op) implements Node {}
    private record Bol() implements Node {}
    private record Eol() implements Node {}

    // ---- NFA ----
    private int[] type = new int[16], arg = new int[16], out1 = new int[16], out2 = new int[16];
    private int n;
    private final List<Cls> classes = new ArrayList<>();
    private int start;

    private Regex(String p) {
        Parser ps = new Parser(p);
        Node ast = ps.parseAlt(0);
        if (ps.i < p.length()) {
            throw new IllegalArgumentException("unbalanced parenthesis");
        }
        int match = add(MATCH, 0, -1, -1);
        start = build(ast, match);
    }

    public static Regex compile(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("null pattern");
        }
        return new Regex(pattern);
    }

    private int add(int t, int a, int o1, int o2) {
        if (n == type.length) {
            int m = n * 2;
            type = Arrays.copyOf(type, m);
            arg = Arrays.copyOf(arg, m);
            out1 = Arrays.copyOf(out1, m);
            out2 = Arrays.copyOf(out2, m);
        }
        type[n] = t; arg[n] = a; out1[n] = o1; out2[n] = o2;
        return n++;
    }

    /** Builds states for node so that on success control goes to next; returns entry state. */
    private int build(Node node, int next) {
        switch (node) {
            case Lit l:
                return add(CHAR, l.c(), next, -1);
            case Any a:
                return add(ANY, 0, next, -1);
            case Cls c:
                classes.add(c);
                return add(CLASS, classes.size() - 1, next, -1);
            case Bol b:
                return add(BOL, 0, next, -1);
            case Eol e:
                return add(EOL, 0, next, -1);
            case Cat c: {
                int nx = next;
                for (int k = c.items().size() - 1; k >= 0; k--) {
                    nx = build(c.items().get(k), nx);
                }
                return nx;
            }
            case Alt a: {
                int entry = build(a.alts().get(a.alts().size() - 1), next);
                for (int k = a.alts().size() - 2; k >= 0; k--) {
                    int s = build(a.alts().get(k), next);
                    entry = add(SPLIT, 0, s, entry);
                }
                return entry;
            }
            case Rep r: {
                if (r.op() == '?') {
                    int body = build(r.child(), next);
                    return add(SPLIT, 0, body, next);
                }
                int split = add(SPLIT, 0, -1, next);
                int body = build(r.child(), split);
                out1[split] = body;
                return r.op() == '*' ? split : body;
            }
        }
    }

    // ---- Parser ----
    private static final class Parser {
        final String p;
        int i;

        Parser(String p) {
            this.p = p;
        }

        static boolean isSpecial(char c) {
            return "()[]|*+?.^$\\".indexOf(c) >= 0;
        }

        Node parseAlt(int depth) {
            List<Node> alts = new ArrayList<>();
            alts.add(parseCat(depth));
            while (i < p.length() && p.charAt(i) == '|') {
                i++;
                alts.add(parseCat(depth));
            }
            return alts.size() == 1 ? alts.get(0) : new Alt(alts);
        }

        Node parseCat(int depth) {
            List<Node> items = new ArrayList<>();
            while (i < p.length()) {
                char c = p.charAt(i);
                if (c == '|') {
                    break;
                }
                if (c == ')') {
                    if (depth == 0) {
                        throw new IllegalArgumentException("unmatched )");
                    }
                    break;
                }
                if (c == '*' || c == '+' || c == '?') {
                    throw new IllegalArgumentException("quantifier without atom");
                }
                Node atom = parseAtom(depth);
                if (i < p.length()) {
                    char q = p.charAt(i);
                    if (q == '*' || q == '+' || q == '?') {
                        i++;
                        atom = new Rep(atom, q);
                        if (i < p.length()) {
                            char q2 = p.charAt(i);
                            if (q2 == '*' || q2 == '+' || q2 == '?') {
                                throw new IllegalArgumentException("double quantifier");
                            }
                        }
                    }
                }
                items.add(atom);
            }
            return items.size() == 1 ? items.get(0) : new Cat(items);
        }

        Node parseAtom(int depth) {
            char c = p.charAt(i++);
            switch (c) {
                case '(': {
                    Node inner = parseAlt(depth + 1);
                    if (i >= p.length() || p.charAt(i) != ')') {
                        throw new IllegalArgumentException("unbalanced (");
                    }
                    i++;
                    return inner;
                }
                case '[':
                    return parseClass();
                case ']':
                    throw new IllegalArgumentException("unmatched ]");
                case '.':
                    return new Any();
                case '^':
                    return new Bol();
                case '$':
                    return new Eol();
                case '\\':
                    if (i >= p.length()) {
                        throw new IllegalArgumentException("trailing backslash");
                    }
                    return new Lit(p.charAt(i++));
                default:
                    return new Lit(c);
            }
        }

        char classChar() {
            if (i >= p.length()) {
                throw new IllegalArgumentException("unterminated class");
            }
            char c = p.charAt(i++);
            if (c == '\\') {
                if (i >= p.length()) {
                    throw new IllegalArgumentException("trailing backslash");
                }
                c = p.charAt(i++);
            }
            return c;
        }

        Node parseClass() {
            boolean neg = false;
            if (i < p.length() && p.charAt(i) == '^') {
                neg = true;
                i++;
            }
            List<Integer> r = new ArrayList<>();
            while (true) {
                if (i >= p.length()) {
                    throw new IllegalArgumentException("unterminated class");
                }
                if (p.charAt(i) == ']') {
                    i++;
                    break;
                }
                char lo = classChar();
                char hi = lo;
                if (i + 1 < p.length() && p.charAt(i) == '-' && p.charAt(i + 1) != ']') {
                    i++;
                    hi = classChar();
                    if (hi < lo) {
                        throw new IllegalArgumentException("reversed range");
                    }
                }
                r.add((int) lo);
                r.add((int) hi);
            }
            if (r.isEmpty()) {
                throw new IllegalArgumentException("empty class");
            }
            return new Cls(neg, r.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    // ---- Matching ----
    private boolean consumes(int s, char ch) {
        switch (type[s]) {
            case CHAR:
                return arg[s] == ch;
            case ANY:
                return true;
            default: {
                Cls c = classes.get(arg[s]);
                boolean in = false;
                int[] r = c.ranges();
                for (int k = 0; k < r.length; k += 2) {
                    if (ch >= r[k] && ch <= r[k + 1]) {
                        in = true;
                        break;
                    }
                }
                return in != c.neg();
            }
        }
    }

    private final class Sim {
        final int[] mark = new int[n];
        final int[] stack = new int[n * 2 + 2];
        int gen;
        int[] cur = new int[n], nxt = new int[n];
        int curN, nxtN;
        boolean matched;
        final int len;

        Sim(int len) {
            this.len = len;
        }

        /** Adds closure of s at position pos into the next list (using current generation). */
        void closure(int s0, int pos) {
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
                        if (pos == len) {
                            stack[sp++] = out1[s];
                        }
                        break;
                    case MATCH:
                        matched = true;
                        break;
                    default:
                        nxt[nxtN++] = s;
                }
            }
        }

        void swap() {
            int[] t = cur; cur = nxt; nxt = t;
            curN = nxtN; nxtN = 0;
        }

        boolean run(String in, boolean find) {
            gen = 1;
            Arrays.fill(mark, 0);
            nxtN = 0;
            matched = false;
            closure(start, 0);
            swap();
            if (find && matched) {
                return true;
            }
            for (int i = 0; i < len; i++) {
                char ch = in.charAt(i);
                gen++;
                matched = false;
                for (int k = 0; k < curN; k++) {
                    int s = cur[k];
                    if (consumes(s, ch)) {
                        closure(out1[s], i + 1);
                    }
                }
                if (find) {
                    closure(start, i + 1);
                    if (matched) {
                        return true;
                    }
                }
                swap();
                if (curN == 0 && !matched && !find) {
                    return false;
                }
            }
            return matched;
        }
    }

    public boolean matches(String input) {
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        return new Sim(input.length()).run(input, false);
    }

    public boolean find(String input) {
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        return new Sim(input.length()).run(input, true);
    }
}
