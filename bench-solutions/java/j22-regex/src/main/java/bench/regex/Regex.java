package bench.regex;

import java.util.ArrayList;
import java.util.List;

/** Linear-time (Thompson NFA simulation) regular expression matcher. */
public final class Regex {
    private static final int CHAR = 0;
    private static final int ANY = 1;
    private static final int CLASS = 2;
    private static final int SPLIT = 3;
    private static final int BOL = 4;
    private static final int EOL = 5;
    private static final int MATCH = 6;

    // ----- AST -----
    private sealed interface Node permits Empty, Lit, Dot, Cls, Seq, Alt, Rep, Bol, Eol { }

    private record Empty() implements Node { }

    private record Lit(char c) implements Node { }

    private record Dot() implements Node { }

    private record Cls(boolean negated, char[] lo, char[] hi) implements Node { }

    private record Seq(List<Node> items) implements Node { }

    private record Alt(List<Node> options) implements Node { }

    /** kind: '*', '+' or '?'. */
    private record Rep(char kind, Node body) implements Node { }

    private record Bol() implements Node { }

    private record Eol() implements Node { }

    // ----- NFA -----
    private int[] type = new int[16];
    private int[] out1 = new int[16];
    private int[] out2 = new int[16];
    private char[] ch = new char[16];
    private Cls[] cls = new Cls[16];
    private int count;
    private int start;

    private Regex(String pattern) {
        Node ast = new Parser(pattern).parse();
        int match = add(MATCH, -1, -1, '\0', null);
        start = compile(ast, match);
    }

    /** Compiles a pattern; throws IllegalArgumentException when it is malformed. */
    public static Regex compile(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("null pattern");
        }
        return new Regex(pattern);
    }

    private int add(int t, int o1, int o2, char c, Cls k) {
        if (count == type.length) {
            int n = count * 2;
            type = java.util.Arrays.copyOf(type, n);
            out1 = java.util.Arrays.copyOf(out1, n);
            out2 = java.util.Arrays.copyOf(out2, n);
            ch = java.util.Arrays.copyOf(ch, n);
            cls = java.util.Arrays.copyOf(cls, n);
        }
        type[count] = t;
        out1[count] = o1;
        out2[count] = o2;
        ch[count] = c;
        cls[count] = k;
        return count++;
    }

    private int compile(Node n, int next) {
        switch (n) {
            case Empty e -> {
                return next;
            }
            case Lit l -> {
                return add(CHAR, next, -1, l.c(), null);
            }
            case Dot d -> {
                return add(ANY, next, -1, '\0', null);
            }
            case Cls c -> {
                return add(CLASS, next, -1, '\0', c);
            }
            case Bol b -> {
                return add(BOL, next, -1, '\0', null);
            }
            case Eol e -> {
                return add(EOL, next, -1, '\0', null);
            }
            case Seq s -> {
                int cur = next;
                for (int i = s.items().size() - 1; i >= 0; i--) {
                    cur = compile(s.items().get(i), cur);
                }
                return cur;
            }
            case Alt a -> {
                int cur = compile(a.options().get(a.options().size() - 1), next);
                for (int i = a.options().size() - 2; i >= 0; i--) {
                    int left = compile(a.options().get(i), next);
                    cur = add(SPLIT, left, cur, '\0', null);
                }
                return cur;
            }
            case Rep r -> {
                switch (r.kind()) {
                    case '*' -> {
                        int sp = add(SPLIT, -1, next, '\0', null);
                        int body = compile(r.body(), sp);
                        out1[sp] = body;
                        return sp;
                    }
                    case '+' -> {
                        int sp = add(SPLIT, -1, next, '\0', null);
                        int body = compile(r.body(), sp);
                        out1[sp] = body;
                        return body;
                    }
                    default -> {
                        int body = compile(r.body(), next);
                        return add(SPLIT, body, next, '\0', null);
                    }
                }
            }
        }
    }

    // ----- parser -----
    private static final class Parser {
        private final String p;
        private int pos;

        Parser(String p) {
            this.p = p;
        }

        Node parse() {
            Node n = alternation();
            if (pos < p.length()) {
                // only an unmatched ')' can stop the alternation early
                throw new IllegalArgumentException("unbalanced ')' at " + pos);
            }
            return n;
        }

        private Node alternation() {
            List<Node> opts = new ArrayList<>();
            opts.add(concat());
            while (pos < p.length() && p.charAt(pos) == '|') {
                pos++;
                opts.add(concat());
            }
            return opts.size() == 1 ? opts.get(0) : new Alt(opts);
        }

        private Node concat() {
            List<Node> items = new ArrayList<>();
            while (pos < p.length()) {
                char c = p.charAt(pos);
                if (c == '|' || c == ')') {
                    break;
                }
                if (c == '*' || c == '+' || c == '?') {
                    throw new IllegalArgumentException("quantifier without atom at " + pos);
                }
                Node atom = atom();
                if (pos < p.length()) {
                    char q = p.charAt(pos);
                    if (q == '*' || q == '+' || q == '?') {
                        pos++;
                        atom = new Rep(q, atom);
                        if (pos < p.length()) {
                            char q2 = p.charAt(pos);
                            if (q2 == '*' || q2 == '+' || q2 == '?') {
                                throw new IllegalArgumentException("repeated quantifier at " + pos);
                            }
                        }
                    }
                }
                items.add(atom);
            }
            if (items.isEmpty()) {
                return new Empty();
            }
            return items.size() == 1 ? items.get(0) : new Seq(items);
        }

        private Node atom() {
            char c = p.charAt(pos++);
            switch (c) {
                case '(' -> {
                    Node inner = alternation();
                    if (pos >= p.length() || p.charAt(pos) != ')') {
                        throw new IllegalArgumentException("unbalanced '('");
                    }
                    pos++;
                    return inner;
                }
                case ']' -> throw new IllegalArgumentException("unmatched ']'");
                case '[' -> {
                    return charClass();
                }
                case '.' -> {
                    return new Dot();
                }
                case '^' -> {
                    return new Bol();
                }
                case '$' -> {
                    return new Eol();
                }
                case '\\' -> {
                    if (pos >= p.length()) {
                        throw new IllegalArgumentException("trailing backslash");
                    }
                    return new Lit(p.charAt(pos++));
                }
                default -> {
                    return new Lit(c);
                }
            }
        }

        private char classChar() {
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

        private Node charClass() {
            boolean neg = false;
            if (pos < p.length() && p.charAt(pos) == '^') {
                neg = true;
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
                    if (lo > hi) {
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
            return new Cls(neg, los, his);
        }
    }

    // ----- matching -----
    private boolean consumes(int s, char c) {
        switch (type[s]) {
            case CHAR:
                return ch[s] == c;
            case ANY:
                return true;
            case CLASS: {
                Cls k = cls[s];
                boolean in = false;
                for (int i = 0; i < k.lo().length; i++) {
                    if (c >= k.lo()[i] && c <= k.hi()[i]) {
                        in = true;
                        break;
                    }
                }
                return in != k.negated();
            }
            default:
                return false;
        }
    }

    private final class Sim {
        final int[] mark = new int[count];
        int stamp;
        final int[] stack = new int[count + 1];
        boolean matched;

        /** Adds the epsilon closure of s at input position pos to list; returns new list size. */
        int addClosure(int s0, int pos, int n, int[] list, int size) {
            int sp = 0;
            stack[sp++] = s0;
            while (sp > 0) {
                int s = stack[--sp];
                if (mark[s] == stamp) {
                    continue;
                }
                mark[s] = stamp;
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
                        list[size++] = s;
                        break;
                    default:
                        list[size++] = s;
                        break;
                }
            }
            return size;
        }
    }

    private boolean run(String input, boolean unanchored) {
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        int n = input.length();
        Sim sim = new Sim();
        int[] cur = new int[count];
        int[] nxt = new int[count];
        sim.stamp = 1;
        int cs = sim.addClosure(start, 0, n, cur, 0);
        for (int i = 0; ; i++) {
            if (sim.matched && (unanchored || i == n)) {
                return true;
            }
            if (i == n) {
                return false;
            }
            char c = input.charAt(i);
            sim.stamp++;
            sim.matched = false;
            int ns = 0;
            for (int k = 0; k < cs; k++) {
                int s = cur[k];
                if (type[s] != MATCH && consumes(s, c)) {
                    ns = sim.addClosure(out1[s], i + 1, n, nxt, ns);
                }
            }
            if (unanchored) {
                ns = sim.addClosure(start, i + 1, n, nxt, ns);
            }
            int[] t = cur;
            cur = nxt;
            nxt = t;
            cs = ns;
            if (cs == 0 && !unanchored) {
                return false;
            }
        }
    }

    /** True when the entire input matches. */
    public boolean matches(String input) {
        return run(input, false);
    }

    /** True when some substring (possibly empty) of the input matches. */
    public boolean find(String input) {
        return run(input, true);
    }
}
