package bench.regex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Linear-time (Thompson NFA simulation) regular expression matcher. */
public final class Regex {
    private static final int CHAR = 0, ANY = 1, CLASS = 2, SPLIT = 3, BOL = 4, EOL = 5, MATCH = 6;

    private int[] type;
    private char[] ch;
    private int[] out1;
    private int[] out2;
    private int[][] cls; // class ranges as [lo0,hi0,lo1,hi1,...]
    private boolean[] negated;
    private int count;
    private int start;

    private Regex() {
    }

    // ---------- AST ----------
    private abstract static class Node {
    }

    private static final class Lit extends Node {
        final int kind; // CHAR, ANY, CLASS, BOL, EOL
        final char c;
        final int[] ranges;
        final boolean neg;

        Lit(int kind, char c, int[] ranges, boolean neg) {
            this.kind = kind;
            this.c = c;
            this.ranges = ranges;
            this.neg = neg;
        }
    }

    private static final class Cat extends Node {
        final List<Node> items;

        Cat(List<Node> items) {
            this.items = items;
        }
    }

    private static final class Alt extends Node {
        final List<Node> items;

        Alt(List<Node> items) {
            this.items = items;
        }
    }

    private static final class Rep extends Node {
        final Node child;
        final char op;

        Rep(Node child, char op) {
            this.child = child;
            this.op = op;
        }
    }

    // ---------- Parser ----------
    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        Node parseAlt(int depth) {
            List<Node> alts = new ArrayList<>();
            alts.add(parseCat(depth));
            while (pos < s.length() && s.charAt(pos) == '|') {
                pos++;
                alts.add(parseCat(depth));
            }
            return alts.size() == 1 ? alts.get(0) : new Alt(alts);
        }

        Node parseCat(int depth) {
            List<Node> items = new ArrayList<>();
            while (pos < s.length()) {
                char c = s.charAt(pos);
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
                Node atom = parseAtom(depth);
                if (pos < s.length()) {
                    char q = s.charAt(pos);
                    if (q == '*' || q == '+' || q == '?') {
                        pos++;
                        atom = new Rep(atom, q);
                        if (pos < s.length()) {
                            char q2 = s.charAt(pos);
                            if (q2 == '*' || q2 == '+' || q2 == '?') {
                                throw new IllegalArgumentException("repeated quantifier at " + pos);
                            }
                        }
                    }
                }
                items.add(atom);
            }
            return items.size() == 1 ? items.get(0) : new Cat(items);
        }

        Node parseAtom(int depth) {
            char c = s.charAt(pos++);
            switch (c) {
                case '(': {
                    Node inner = parseAlt(depth + 1);
                    if (pos >= s.length() || s.charAt(pos) != ')') {
                        throw new IllegalArgumentException("unbalanced '('");
                    }
                    pos++;
                    return inner;
                }
                case ']':
                    throw new IllegalArgumentException("unmatched ']'");
                case '[':
                    return parseClass();
                case '.':
                    return new Lit(ANY, '\0', null, false);
                case '^':
                    return new Lit(BOL, '\0', null, false);
                case '$':
                    return new Lit(EOL, '\0', null, false);
                case '\\':
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("trailing backslash");
                    }
                    return new Lit(CHAR, s.charAt(pos++), null, false);
                default:
                    return new Lit(CHAR, c, null, false);
            }
        }

        char classChar() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unterminated class");
            }
            char c = s.charAt(pos++);
            if (c == '\\') {
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated class");
                }
                c = s.charAt(pos++);
            }
            return c;
        }

        Node parseClass() {
            boolean neg = false;
            if (pos < s.length() && s.charAt(pos) == '^') {
                neg = true;
                pos++;
            }
            List<int[]> ranges = new ArrayList<>();
            while (true) {
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated class");
                }
                if (s.charAt(pos) == ']') {
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
                ranges.add(new int[] {lo, hi});
            }
            if (ranges.isEmpty()) {
                throw new IllegalArgumentException("empty class");
            }
            int[] flat = new int[ranges.size() * 2];
            for (int i = 0; i < ranges.size(); i++) {
                flat[2 * i] = ranges.get(i)[0];
                flat[2 * i + 1] = ranges.get(i)[1];
            }
            return new Lit(CLASS, '\0', flat, neg);
        }
    }

    // ---------- NFA construction (continuation style, built back to front) ----------
    private int newState(int t, char c, int o1, int o2) {
        if (count == type.length) {
            int n = count * 2;
            type = Arrays.copyOf(type, n);
            ch = Arrays.copyOf(ch, n);
            out1 = Arrays.copyOf(out1, n);
            out2 = Arrays.copyOf(out2, n);
            cls = Arrays.copyOf(cls, n);
            negated = Arrays.copyOf(negated, n);
        }
        type[count] = t;
        ch[count] = c;
        out1[count] = o1;
        out2[count] = o2;
        return count++;
    }

    private int build(Node n, int next) {
        if (n instanceof Lit l) {
            int s = newState(l.kind, l.c, next, -1);
            cls[s] = l.ranges;
            negated[s] = l.neg;
            return s;
        } else if (n instanceof Cat c) {
            int cur = next;
            for (int i = c.items.size() - 1; i >= 0; i--) {
                cur = build(c.items.get(i), cur);
            }
            return cur;
        } else if (n instanceof Alt a) {
            int cur = build(a.items.get(a.items.size() - 1), next);
            for (int i = a.items.size() - 2; i >= 0; i--) {
                int left = build(a.items.get(i), next);
                cur = newState(SPLIT, '\0', left, cur);
            }
            return cur;
        } else {
            Rep r = (Rep) n;
            if (r.op == '?') {
                int body = build(r.child, next);
                return newState(SPLIT, '\0', body, next);
            }
            int split = newState(SPLIT, '\0', -1, next);
            int body = build(r.child, split);
            out1[split] = body;
            return r.op == '*' ? split : body;
        }
    }

    /** Compiles a pattern; throws IllegalArgumentException for invalid syntax. */
    public static Regex compile(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("null pattern");
        }
        Parser p = new Parser(pattern);
        Node root = p.parseAlt(0);
        if (p.pos < pattern.length()) {
            throw new IllegalArgumentException("unbalanced ')' at " + p.pos);
        }
        Regex re = new Regex();
        re.type = new int[16];
        re.ch = new char[16];
        re.out1 = new int[16];
        re.out2 = new int[16];
        re.cls = new int[16][];
        re.negated = new boolean[16];
        int match = re.newState(MATCH, '\0', -1, -1);
        re.start = re.build(root, match);
        return re;
    }

    // ---------- Simulation ----------
    private final class StateSet {
        final int[] list = new int[count];
        int size;
        final int[] mark = new int[count];
        int gen = 1;
        boolean matched;
        final int[] stack = new int[count * 2 + 4];

        void clear() {
            size = 0;
            matched = false;
            gen++;
        }

        void addClosure(int s0, boolean atStart, boolean atEnd) {
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
                        if (atStart) {
                            stack[sp++] = out1[s];
                        }
                        break;
                    case EOL:
                        if (atEnd) {
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
        }
    }

    private boolean accepts(int s, char c) {
        switch (type[s]) {
            case CHAR:
                return ch[s] == c;
            case ANY:
                return true;
            default: {
                int[] r = cls[s];
                boolean in = false;
                for (int i = 0; i < r.length; i += 2) {
                    if (c >= r[i] && c <= r[i + 1]) {
                        in = true;
                        break;
                    }
                }
                return in != negated[s];
            }
        }
    }

    private boolean run(String input, boolean find) {
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        int n = input.length();
        StateSet cur = new StateSet();
        StateSet nxt = new StateSet();
        cur.addClosure(start, true, n == 0);
        for (int i = 0; ; i++) {
            if (find && cur.matched) {
                return true;
            }
            if (i == n) {
                return cur.matched;
            }
            char c = input.charAt(i);
            nxt.clear();
            boolean atEnd = i + 1 == n;
            for (int k = 0; k < cur.size; k++) {
                int s = cur.list[k];
                if (accepts(s, c)) {
                    nxt.addClosure(out1[s], false, atEnd);
                }
            }
            if (find) {
                nxt.addClosure(start, false, atEnd);
            } else if (nxt.size == 0 && !nxt.matched) {
                return false;
            }
            StateSet t = cur;
            cur = nxt;
            nxt = t;
        }
    }

    /** True if the whole input matches. */
    public boolean matches(String input) {
        return run(input, false);
    }

    /** True if some substring (possibly empty) matches. */
    public boolean find(String input) {
        return run(input, true);
    }
}
