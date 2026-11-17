package bench.regex;

import java.util.ArrayList;
import java.util.List;

/** Thompson-NFA regex engine: O(|pattern| * |input|) matching. */
public final class Regex {
    private static final int CHAR = 0, SPLIT = 1, BOL = 2, EOL = 3, MATCH = 4;

    // ---- AST ----
    private sealed interface Node permits Empty, Atom, Cat, Alt, Rep, Anchor {}
    private record Empty() implements Node {}
    private record Atom(CharSet set) implements Node {}
    private record Cat(Node a, Node b) implements Node {}
    private record Alt(Node a, Node b) implements Node {}
    private record Rep(Node n, char kind) implements Node {}
    private record Anchor(boolean start) implements Node {}

    private static final class CharSet {
        final boolean any;
        final boolean negate;
        final List<char[]> ranges;

        CharSet(boolean any, boolean negate, List<char[]> ranges) {
            this.any = any;
            this.negate = negate;
            this.ranges = ranges;
        }

        boolean has(char c) {
            if (any) return true;
            boolean in = false;
            for (char[] r : ranges) {
                if (c >= r[0] && c <= r[1]) { in = true; break; }
            }
            return in != negate;
        }
    }

    // ---- NFA ----
    private int[] type = new int[16];
    private int[] out1 = new int[16];
    private int[] out2 = new int[16];
    private CharSet[] sets = new CharSet[16];
    private int count;
    private int start;
    private int matchState;

    private int add(int t, int o1, int o2, CharSet s) {
        if (count == type.length) {
            int n = count * 2;
            type = java.util.Arrays.copyOf(type, n);
            out1 = java.util.Arrays.copyOf(out1, n);
            out2 = java.util.Arrays.copyOf(out2, n);
            sets = java.util.Arrays.copyOf(sets, n);
        }
        type[count] = t;
        out1[count] = o1;
        out2[count] = o2;
        sets[count] = s;
        return count++;
    }

    private Regex(Node ast) {
        matchState = add(MATCH, -1, -1, null);
        start = compile(ast, matchState);
    }

    private int compile(Node n, int next) {
        if (n instanceof Empty) return next;
        if (n instanceof Atom a) return add(CHAR, next, -1, a.set());
        if (n instanceof Cat c) return compile(c.a(), compile(c.b(), next));
        if (n instanceof Alt a) {
            int x = compile(a.a(), next);
            int y = compile(a.b(), next);
            return add(SPLIT, x, y, null);
        }
        if (n instanceof Anchor an) return add(an.start() ? BOL : EOL, next, -1, null);
        Rep r = (Rep) n;
        switch (r.kind()) {
            case '?': {
                int body = compile(r.n(), next);
                return add(SPLIT, body, next, null);
            }
            case '*': {
                int s = add(SPLIT, -1, next, null);
                out1[s] = compile(r.n(), s);
                return s;
            }
            default: { // '+'
                int s = add(SPLIT, -1, next, null);
                int body = compile(r.n(), s);
                out1[s] = body;
                return body;
            }
        }
    }

    // ---- parser ----
    private static final class Parser {
        private final String p;
        private int pos;

        Parser(String p) { this.p = p; }

        Node parse() {
            Node n = alt();
            if (pos < p.length()) throw new IllegalArgumentException("unbalanced )");
            return n;
        }

        private Node alt() {
            Node left = concat();
            while (pos < p.length() && p.charAt(pos) == '|') {
                pos++;
                left = new Alt(left, concat());
            }
            return left;
        }

        private Node concat() {
            Node result = new Empty();
            boolean first = true;
            while (pos < p.length()) {
                char c = p.charAt(pos);
                if (c == '|' || c == ')') break;
                Node atom = atom();
                while (pos < p.length() && isQuant(p.charAt(pos))) {
                    atom = new Rep(atom, p.charAt(pos++));
                    if (pos < p.length() && isQuant(p.charAt(pos))) throw new IllegalArgumentException("double quantifier");
                }
                result = first ? atom : new Cat(result, atom);
                first = false;
            }
            return result;
        }

        private static boolean isQuant(char c) { return c == '*' || c == '+' || c == '?'; }

        private Node atom() {
            char c = p.charAt(pos++);
            switch (c) {
                case '*': case '+': case '?':
                    throw new IllegalArgumentException("quantifier without atom");
                case '(': {
                    Node inner = alt();
                    if (pos >= p.length() || p.charAt(pos) != ')') throw new IllegalArgumentException("unbalanced (");
                    pos++;
                    return inner;
                }
                case ']': throw new IllegalArgumentException("unmatched ]");
                case '[': return charClass();
                case '.': return new Atom(new CharSet(true, false, List.of()));
                case '^': return new Anchor(true);
                case '$': return new Anchor(false);
                case '\\': {
                    if (pos >= p.length()) throw new IllegalArgumentException("trailing backslash");
                    return lit(p.charAt(pos++));
                }
                default: return lit(c);
            }
        }

        private static Node lit(char c) {
            return new Atom(new CharSet(false, false, List.of(new char[] {c, c})));
        }

        private char classChar() {
            if (pos >= p.length()) throw new IllegalArgumentException("unterminated class");
            char c = p.charAt(pos++);
            if (c == '\\') {
                if (pos >= p.length()) throw new IllegalArgumentException("trailing backslash");
                return p.charAt(pos++);
            }
            return c;
        }

        private Node charClass() {
            boolean negate = false;
            if (pos < p.length() && p.charAt(pos) == '^') { negate = true; pos++; }
            List<char[]> ranges = new ArrayList<>();
            while (true) {
                if (pos >= p.length()) throw new IllegalArgumentException("unterminated class");
                if (p.charAt(pos) == ']') { pos++; break; }
                char lo = classChar();
                if (pos + 1 < p.length() && p.charAt(pos) == '-' && p.charAt(pos + 1) != ']') {
                    pos++;
                    char hi = classChar();
                    if (hi < lo) throw new IllegalArgumentException("reversed range");
                    ranges.add(new char[] {lo, hi});
                } else {
                    ranges.add(new char[] {lo, lo});
                }
            }
            if (ranges.isEmpty()) throw new IllegalArgumentException("empty class");
            return new Atom(new CharSet(false, negate, ranges));
        }
    }

    public static Regex compile(String pattern) {
        if (pattern == null) throw new IllegalArgumentException("null pattern");
        return new Regex(new Parser(pattern).parse());
    }

    // ---- simulation ----
    private final class Sim {
        int[] cur = new int[count], nxt = new int[count];
        int curN, nxtN;
        final int[] mark = new int[count];
        int gen = 1;
        final int[] stack = new int[count + 1];
        final String in;
        boolean matched;

        Sim(String in) { this.in = in; }

        /** Adds state s (and its epsilon closure at position pos) to the next list. */
        void addTo(int s, int pos, boolean toNext) {
            int sp = 0;
            stack[sp++] = s;
            while (sp > 0) {
                int u = stack[--sp];
                if (mark[u] == gen) continue;
                mark[u] = gen;
                switch (type[u]) {
                    case SPLIT:
                        stack[sp++] = out2[u];
                        stack[sp++] = out1[u];
                        break;
                    case BOL:
                        if (pos == 0) stack[sp++] = out1[u];
                        break;
                    case EOL:
                        if (pos == in.length()) stack[sp++] = out1[u];
                        break;
                    case MATCH:
                        matched = true;
                        break;
                    default:
                        if (toNext) nxt[nxtN++] = u; else cur[curN++] = u;
                }
            }
        }

        boolean run(boolean find) {
            int n = in.length();
            gen++;
            curN = 0;
            matched = false;
            addTo(start, 0, false);
            for (int i = 0; i < n; i++) {
                if (find && matched) return true;
                char c = in.charAt(i);
                gen++;
                nxtN = 0;
                matched = false;
                for (int k = 0; k < curN; k++) {
                    int s = cur[k];
                    if (sets[s].has(c)) addTo(out1[s], i + 1, true);
                }
                if (find) addTo(start, i + 1, true);
                int[] t = cur; cur = nxt; nxt = t;
                curN = nxtN;
                if (find && matched) return true;
            }
            return matched;
        }
    }

    public boolean matches(String input) {
        if (input == null) throw new IllegalArgumentException("null input");
        return new Sim(input).run(false);
    }

    public boolean find(String input) {
        if (input == null) throw new IllegalArgumentException("null input");
        return new Sim(input).run(true);
    }
}
