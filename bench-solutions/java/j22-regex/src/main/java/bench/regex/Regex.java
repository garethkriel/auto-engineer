package bench.regex;

import java.util.ArrayList;
import java.util.List;

/** Thompson-NFA regular expressions with matching in O(|pattern| * |input|). */
public final class Regex {
    private static final int CHAR = 0;
    private static final int ANY = 1;
    private static final int CLASS = 2;
    private static final int SPLIT = 3;
    private static final int EPS = 4;
    private static final int BOL = 5;
    private static final int EOL = 6;
    private static final int MATCH = 7;

    // ---- AST ----
    private interface Node {
    }

    private record Lit(char c) implements Node {
    }

    private record AnyChar() implements Node {
    }

    private record CharClass(boolean negated, char[] lo, char[] hi) implements Node {
    }

    private record Seq(List<Node> items) implements Node {
    }

    private record Alt(List<Node> options) implements Node {
    }

    private record Rep(Node inner, char kind) implements Node {
    }

    private record Anchor(boolean start) implements Node {
    }

    // ---- NFA ----
    private int[] type = new int[16];
    private int[] out1 = new int[16];
    private int[] out2 = new int[16];
    private char[] ch = new char[16];
    private CharClass[] cls = new CharClass[16];
    private int count;
    private final int start;

    private Regex(String pattern) {
        Parser p = new Parser(pattern);
        Node ast = p.parseAlt();
        if (p.pos < pattern.length()) {
            throw new IllegalArgumentException("unbalanced ')' at " + p.pos);
        }
        int m = add(MATCH, -1, -1);
        start = compile(ast, m);
    }

    public static Regex compile(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("null pattern");
        }
        return new Regex(pattern);
    }

    private int add(int t, int o1, int o2) {
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
        return count++;
    }

    /** Compiles {@code n} so that it continues at state {@code next}; returns its entry state. */
    private int compile(Node n, int next) {
        if (n instanceof Lit l) {
            int s = add(CHAR, next, -1);
            ch[s] = l.c();
            return s;
        }
        if (n instanceof AnyChar) {
            return add(ANY, next, -1);
        }
        if (n instanceof CharClass c) {
            int s = add(CLASS, next, -1);
            cls[s] = c;
            return s;
        }
        if (n instanceof Anchor a) {
            return add(a.start() ? BOL : EOL, next, -1);
        }
        if (n instanceof Seq q) {
            int cur = next;
            for (int i = q.items().size() - 1; i >= 0; i--) {
                cur = compile(q.items().get(i), cur);
            }
            return cur;
        }
        if (n instanceof Alt a) {
            int cur = compile(a.options().get(a.options().size() - 1), next);
            for (int i = a.options().size() - 2; i >= 0; i--) {
                int left = compile(a.options().get(i), next);
                cur = add(SPLIT, left, cur);
            }
            return cur;
        }
        Rep r = (Rep) n;
        switch (r.kind()) {
            case '?' -> {
                int body = compile(r.inner(), next);
                return add(SPLIT, body, next);
            }
            case '*' -> {
                int split = add(SPLIT, -1, next);
                out1[split] = compile(r.inner(), split);
                return split;
            }
            default -> { // '+'
                int split = add(SPLIT, -1, next);
                int body = compile(r.inner(), split);
                out1[split] = body;
                return body;
            }
        }
    }

    // ---- matching ----

    private final class Sim {
        final int[] mark = new int[count];
        int gen = 0;
        int[] cur = new int[count];
        int[] nxt = new int[count];
        int curN;
        int nxtN;
        final int[] stack = new int[count * 2 + 2];

        /** Adds the epsilon closure of s at input position pos to the next list. */
        void addTo(int s0, int pos, int len) {
            int sp = 0;
            stack[sp++] = s0;
            while (sp > 0) {
                int s = stack[--sp];
                if (mark[s] == gen) {
                    continue;
                }
                mark[s] = gen;
                switch (type[s]) {
                    case SPLIT -> {
                        stack[sp++] = out2[s];
                        stack[sp++] = out1[s];
                    }
                    case EPS -> stack[sp++] = out1[s];
                    case BOL -> {
                        if (pos == 0) {
                            stack[sp++] = out1[s];
                        }
                    }
                    case EOL -> {
                        if (pos == len) {
                            stack[sp++] = out1[s];
                        }
                    }
                    default -> nxt[nxtN++] = s;
                }
            }
        }

        boolean run(String in, boolean find) {
            int len = in.length();
            curN = 0;
            nxtN = 0;
            gen++;
            addTo(start, 0, len);
            swap();
            for (int pos = 0; ; pos++) {
                if (hasMatch()) {
                    if (find || pos == len) {
                        return true;
                    }
                }
                if (pos == len) {
                    return false;
                }
                char c = in.charAt(pos);
                gen++;
                nxtN = 0;
                for (int i = 0; i < curN; i++) {
                    int s = cur[i];
                    boolean ok = switch (type[s]) {
                        case CHAR -> ch[s] == c;
                        case ANY -> true;
                        case CLASS -> classMatches(cls[s], c);
                        default -> false;
                    };
                    if (ok) {
                        addTo(out1[s], pos + 1, len);
                    }
                }
                if (find) {
                    addTo(start, pos + 1, len);
                }
                swap();
                if (curN == 0 && !find) {
                    return false;
                }
            }
        }

        boolean hasMatch() {
            for (int i = 0; i < curN; i++) {
                if (type[cur[i]] == MATCH) {
                    return true;
                }
            }
            return false;
        }

        void swap() {
            int[] t = cur;
            cur = nxt;
            nxt = t;
            curN = nxtN;
            nxtN = 0;
        }
    }

    private static boolean classMatches(CharClass c, char x) {
        boolean in = false;
        for (int i = 0; i < c.lo().length; i++) {
            if (x >= c.lo()[i] && x <= c.hi()[i]) {
                in = true;
                break;
            }
        }
        return in != c.negated();
    }

    public boolean matches(String input) {
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        return new Sim().run(input, false);
    }

    public boolean find(String input) {
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        return new Sim().run(input, true);
    }

    // ---- parser ----

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        Node parseAlt() {
            List<Node> options = new ArrayList<>();
            options.add(parseSeq());
            while (pos < s.length() && s.charAt(pos) == '|') {
                pos++;
                options.add(parseSeq());
            }
            return options.size() == 1 ? options.get(0) : new Alt(options);
        }

        Node parseSeq() {
            List<Node> items = new ArrayList<>();
            boolean lastAtom = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '|' || c == ')') {
                    break;
                }
                if (c == '*' || c == '+' || c == '?') {
                    if (!lastAtom) {
                        throw new IllegalArgumentException("misplaced quantifier at " + pos);
                    }
                    pos++;
                    int last = items.size() - 1;
                    items.set(last, new Rep(items.get(last), c));
                    lastAtom = false;
                    continue;
                }
                items.add(atom());
                lastAtom = true;
            }
            return new Seq(items);
        }

        Node atom() {
            char c = s.charAt(pos++);
            switch (c) {
                case '(' -> {
                    Node inner = parseAlt();
                    if (pos >= s.length() || s.charAt(pos) != ')') {
                        throw new IllegalArgumentException("unbalanced '('");
                    }
                    pos++;
                    return inner instanceof Seq q && q.items().isEmpty() ? new Seq(new ArrayList<>()) : inner;
                }
                case '[' -> {
                    return charClass();
                }
                case ']' -> throw new IllegalArgumentException("unmatched ']'");
                case '.' -> {
                    return new AnyChar();
                }
                case '^' -> {
                    return new Anchor(true);
                }
                case '$' -> {
                    return new Anchor(false);
                }
                case '\\' -> {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("trailing backslash");
                    }
                    return new Lit(s.charAt(pos++));
                }
                default -> {
                    return new Lit(c);
                }
            }
        }

        Node charClass() {
            boolean negated = false;
            if (pos < s.length() && s.charAt(pos) == '^') {
                negated = true;
                pos++;
            }
            List<char[]> ranges = new ArrayList<>();
            while (true) {
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated class");
                }
                char c = s.charAt(pos);
                if (c == ']') {
                    pos++;
                    break;
                }
                char lo = classChar();
                char hi = lo;
                if (pos + 1 < s.length() && s.charAt(pos) == '-' && s.charAt(pos + 1) != ']') {
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
            return new CharClass(negated, los, his);
        }

        char classChar() {
            char c = s.charAt(pos++);
            if (c == '\\') {
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("trailing backslash");
                }
                return s.charAt(pos++);
            }
            return c;
        }
    }
}
