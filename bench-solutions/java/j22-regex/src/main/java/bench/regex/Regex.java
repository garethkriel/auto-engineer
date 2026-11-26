package bench.regex;

import java.util.ArrayList;
import java.util.List;

/** Thompson-NFA regular expressions: matching time is O(|pattern| * |input|). */
public final class Regex {
    private static final int CHAR = 0, ANY = 1, CLASS = 2, SPLIT = 3, BOL = 4, EOL = 5, MATCH = 6, EPS = 7;

    private final int[] type;
    private final int[] out1;
    private final int[] out2;
    private final int[] ch;
    private final long[][] sets;
    private final int start;

    // ---- AST ----
    private static final int N_LIT = 0, N_ANY = 1, N_CLASS = 2, N_CAT = 3, N_ALT = 4, N_STAR = 5, N_PLUS = 6, N_QUEST = 7,
        N_BOL = 8, N_EOL = 9;

    private static final class N {
        final int kind;
        int c;
        long[] set;
        N a;
        List<N> kids;
        N(int kind) { this.kind = kind; }
    }

    private static final class Parser {
        final String p;
        int pos;
        Parser(String p) { this.p = p; }

        N parseAlt() {
            List<N> alts = new ArrayList<>();
            alts.add(parseCat());
            while (pos < p.length() && p.charAt(pos) == '|') {
                pos++;
                alts.add(parseCat());
            }
            if (alts.size() == 1) return alts.get(0);
            N n = new N(N_ALT);
            n.kids = alts;
            return n;
        }

        N parseCat() {
            List<N> items = new ArrayList<>();
            while (pos < p.length() && p.charAt(pos) != '|' && p.charAt(pos) != ')') {
                N atom = parseAtom();
                if (pos < p.length() && isQuant(p.charAt(pos))) {
                    char q = p.charAt(pos++);
                    N w = new N(q == '*' ? N_STAR : q == '+' ? N_PLUS : N_QUEST);
                    w.a = atom;
                    atom = w;
                    if (pos < p.length() && isQuant(p.charAt(pos))) throw new IllegalArgumentException("repeated quantifier");
                }
                items.add(atom);
            }
            N n = new N(N_CAT);
            n.kids = items;
            return n;
        }

        N parseAtom() {
            char c = p.charAt(pos++);
            switch (c) {
                case '(': {
                    N inner = parseAlt();
                    if (pos >= p.length() || p.charAt(pos) != ')') throw new IllegalArgumentException("unbalanced (");
                    pos++;
                    return inner;
                }
                case '[':
                    return parseClass();
                case '.':
                    return new N(N_ANY);
                case '\\': {
                    if (pos >= p.length()) throw new IllegalArgumentException("trailing backslash");
                    N n = new N(N_LIT);
                    n.c = p.charAt(pos++);
                    return n;
                }
                case '^':
                    return new N(N_BOL);
                case '$':
                    return new N(N_EOL);
                case '*': case '+': case '?':
                    throw new IllegalArgumentException("quantifier without atom");
                case ']':
                    throw new IllegalArgumentException("unmatched ]");
                default: {
                    N n = new N(N_LIT);
                    n.c = c;
                    return n;
                }
            }
        }

        int classChar() {
            if (pos >= p.length()) throw new IllegalArgumentException("unterminated class");
            char c = p.charAt(pos++);
            if (c == '\\') {
                if (pos >= p.length()) throw new IllegalArgumentException("trailing backslash");
                return p.charAt(pos++);
            }
            return c;
        }

        N parseClass() {
            boolean neg = false;
            if (pos < p.length() && p.charAt(pos) == '^') {
                neg = true;
                pos++;
            }
            long[] set = new long[1024];
            boolean any = false;
            while (true) {
                if (pos >= p.length()) throw new IllegalArgumentException("unterminated class");
                if (p.charAt(pos) == ']') {
                    pos++;
                    break;
                }
                int lo = classChar();
                int hi = lo;
                if (pos + 1 < p.length() && p.charAt(pos) == '-' && p.charAt(pos + 1) != ']') {
                    pos++;
                    hi = classChar();
                    if (hi < lo) throw new IllegalArgumentException("reversed range");
                }
                for (int x = lo; x <= hi; x++) set[x >> 6] |= 1L << (x & 63);
                any = true;
            }
            if (!any) throw new IllegalArgumentException("empty class");
            if (neg) for (int i = 0; i < set.length; i++) set[i] = ~set[i];
            N n = new N(N_CLASS);
            n.set = set;
            return n;
        }

        static boolean isQuant(char c) { return c == '*' || c == '+' || c == '?'; }
    }

    // ---- NFA construction ----
    private static final class Builder {
        final List<int[]> st = new ArrayList<>(); // {type, out1, out2, ch, setIndex}
        final List<long[]> sets = new ArrayList<>();

        int add(int type, int o1, int o2, int c, int setIdx) {
            st.add(new int[]{type, o1, o2, c, setIdx});
            return st.size() - 1;
        }

        int compile(N n, int next) {
            switch (n.kind) {
                case N_LIT: return add(CHAR, next, -1, n.c, -1);
                case N_ANY: return add(ANY, next, -1, 0, -1);
                case N_CLASS:
                    sets.add(n.set);
                    return add(CLASS, next, -1, 0, sets.size() - 1);
                case N_BOL: return add(BOL, next, -1, 0, -1);
                case N_EOL: return add(EOL, next, -1, 0, -1);
                case N_CAT: {
                    int cur = next;
                    for (int i = n.kids.size() - 1; i >= 0; i--) cur = compile(n.kids.get(i), cur);
                    return cur;
                }
                case N_ALT: {
                    int k = n.kids.size();
                    int cur = compile(n.kids.get(k - 1), next);
                    for (int i = k - 2; i >= 0; i--) {
                        int s = compile(n.kids.get(i), next);
                        cur = add(SPLIT, s, cur, 0, -1);
                    }
                    return cur;
                }
                case N_STAR: {
                    int sp = add(SPLIT, -1, next, 0, -1);
                    st.get(sp)[1] = compile(n.a, sp);
                    return sp;
                }
                case N_PLUS: {
                    int sp = add(SPLIT, -1, next, 0, -1);
                    int body = compile(n.a, sp);
                    st.get(sp)[1] = body;
                    return body;
                }
                default: { // N_QUEST
                    int body = compile(n.a, next);
                    return add(SPLIT, body, next, 0, -1);
                }
            }
        }
    }

    private Regex(String pattern) {
        Parser ps = new Parser(pattern);
        N ast = ps.parseAlt();
        if (ps.pos < pattern.length()) throw new IllegalArgumentException("unbalanced )");
        Builder b = new Builder();
        int match = b.add(MATCH, -1, -1, 0, -1);
        start = b.compile(ast, match);
        int n = b.st.size();
        type = new int[n];
        out1 = new int[n];
        out2 = new int[n];
        ch = new int[n];
        sets = new long[b.sets.size()][];
        for (int i = 0; i < n; i++) {
            int[] s = b.st.get(i);
            type[i] = s[0];
            out1[i] = s[1];
            out2[i] = s[2];
            ch[i] = s[3] ;
            if (s[0] == CLASS) {
                sets[s[4]] = b.sets.get(s[4]);
                ch[i] = s[4];
            }
        }
    }

    public static Regex compile(String pattern) {
        if (pattern == null) throw new IllegalArgumentException("null pattern");
        return new Regex(pattern);
    }

    public boolean matches(String input) {
        if (input == null) throw new IllegalArgumentException("null input");
        return run(input, false);
    }

    public boolean find(String input) {
        if (input == null) throw new IllegalArgumentException("null input");
        return run(input, true);
    }

    private int addClosure(int s0, int pos, int len, int[] list, int cnt, int[] mark, int gen, int[] stack) {
        int sp = 0;
        if (mark[s0] != gen) {
            mark[s0] = gen;
            stack[sp++] = s0;
        }
        while (sp > 0) {
            int s = stack[--sp];
            switch (type[s]) {
                case CHAR: case ANY: case CLASS: case MATCH:
                    list[cnt++] = s;
                    break;
                case SPLIT:
                    if (mark[out1[s]] != gen) { mark[out1[s]] = gen; stack[sp++] = out1[s]; }
                    if (mark[out2[s]] != gen) { mark[out2[s]] = gen; stack[sp++] = out2[s]; }
                    break;
                case BOL:
                    if (pos == 0 && mark[out1[s]] != gen) { mark[out1[s]] = gen; stack[sp++] = out1[s]; }
                    break;
                case EOL:
                    if (pos == len && mark[out1[s]] != gen) { mark[out1[s]] = gen; stack[sp++] = out1[s]; }
                    break;
                default:
                    break;
            }
        }
        return cnt;
    }

    private boolean consumes(int s, char c) {
        switch (type[s]) {
            case CHAR: return ch[s] == c;
            case ANY: return true;
            case CLASS: return (sets[ch[s]][c >> 6] & (1L << (c & 63))) != 0;
            default: return false;
        }
    }

    private boolean hasMatch(int[] list, int cnt) {
        for (int i = 0; i < cnt; i++) if (type[list[i]] == MATCH) return true;
        return false;
    }

    private boolean run(String in, boolean find) {
        int n = in.length();
        int size = type.length;
        int[] cur = new int[size];
        int[] nxt = new int[size];
        int[] mark = new int[size];
        int[] stack = new int[size + 1];
        int gen = 1;
        int cn = addClosure(start, 0, n, cur, 0, mark, gen, stack);
        for (int i = 0; i < n; i++) {
            if (find && hasMatch(cur, cn)) return true;
            char c = in.charAt(i);
            gen++;
            int nn = 0;
            for (int k = 0; k < cn; k++) {
                int s = cur[k];
                if (consumes(s, c)) nn = addClosure(out1[s], i + 1, n, nxt, nn, mark, gen, stack);
            }
            if (find) nn = addClosure(start, i + 1, n, nxt, nn, mark, gen, stack);
            int[] t = cur; cur = nxt; nxt = t;
            cn = nn;
            if (cn == 0) return false;
        }
        return hasMatch(cur, cn);
    }
}
