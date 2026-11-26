package bench.sheet;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A small spreadsheet with formulas, ranges, error values and cycle detection. */
public final class Sheet {
    private static final Pattern CELL = Pattern.compile("[A-Z][1-9][0-9]{0,2}");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final Set<String> FUNCS = Set.of("SUM", "MIN", "MAX", "AVERAGE", "COUNT");

    private static final int NUM = 0, TEXT = 1, EMPTY = 2, ERR = 3;

    private record Val(int kind, double num, String text) {
        static Val num(double d) { return new Val(NUM, d, null); }
        static Val err(String s) { return new Val(ERR, 0, s); }
    }

    private static final Val E_PARSE = Val.err("#PARSE!");
    private static final Val E_VALUE = Val.err("#VALUE!");
    private static final Val E_DIV0 = Val.err("#DIV/0!");
    private static final Val E_NUM = Val.err("#NUM!");
    private static final Val E_CYCLE = Val.err("#CYCLE!");
    private static final Val V_EMPTY = new Val(EMPTY, 0, null);

    // ---- AST ----
    private sealed interface Node permits Lit, Ref, Fn, Neg, Bin {}
    private record Lit(double v) implements Node {}
    private record Ref(int key) implements Node {}
    private record Fn(String name, int c1, int r1, int c2, int r2) implements Node {}
    private record Neg(Node a) implements Node {}
    private record Bin(char op, Node l, Node r) implements Node {}

    private static final class Cell {
        final boolean formula;
        final Val literal;      // for non-formula cells
        final Node ast;         // null on parse error
        final BitSet refs = new BitSet();
        final List<int[]> ranges = new ArrayList<>();

        Cell(Val literal) { this.formula = false; this.literal = literal; this.ast = null; }
        Cell(Node ast) { this.formula = true; this.literal = null; this.ast = ast; }
    }

    private static final class ParseFail extends RuntimeException {
        private static final long serialVersionUID = 1L;
        ParseFail() { super(null, null, false, false); }
    }

    private final Map<Integer, Cell> cells = new HashMap<>();
    private final Map<Integer, Val> results = new HashMap<>();

    public Sheet() {}

    private static int keyOf(String cell) {
        if (cell == null || !CELL.matcher(cell).matches()) throw new IllegalArgumentException("bad cell: " + cell);
        return Integer.parseInt(cell.substring(1)) * 26 + (cell.charAt(0) - 'A');
    }

    public void set(String cell, String input) {
        int key = keyOf(cell);
        if (input == null) throw new IllegalArgumentException("null input");
        results.clear();
        if (input.isEmpty()) {
            cells.remove(key);
        } else if (input.charAt(0) == '=') {
            cells.put(key, parseFormula(input.substring(1)));
        } else if (NUMBER.matcher(input).matches()) {
            double d = Double.parseDouble(input);
            cells.put(key, new Cell(Double.isFinite(d) ? Val.num(d) : E_NUM));
        } else {
            cells.put(key, new Cell(new Val(TEXT, 0, input)));
        }
    }

    public String get(String cell) {
        int key = keyOf(cell);
        Cell c = cells.get(key);
        if (c == null) return "";
        if (!c.formula) return display(c.literal);
        compute(key);
        return display(results.get(key));
    }

    private static String display(Val v) {
        return switch (v.kind) {
            case EMPTY -> "";
            case TEXT, ERR -> v.text;
            default -> v.num == 0 ? "0" : BigDecimal.valueOf(v.num).stripTrailingZeros().toPlainString();
        };
    }

    // ---- parsing ----
    private Cell parseFormula(String s) {
        Cell[] holder = new Cell[1];
        Parser p = new Parser(s);
        try {
            Node n = p.parseExpr();
            p.skipWs();
            if (p.pos != s.length()) throw new ParseFail();
            Cell c = new Cell(n);
            c.refs.or(p.refs);
            c.ranges.addAll(p.ranges);
            holder[0] = c;
        } catch (ParseFail | StackOverflowError e) {
            holder[0] = new Cell((Node) null);
        }
        return holder[0];
    }

    private static final class Parser {
        final String s;
        int pos;
        final BitSet refs = new BitSet();
        final List<int[]> ranges = new ArrayList<>();

        Parser(String s) { this.s = s; }

        void skipWs() { while (pos < s.length() && s.charAt(pos) == ' ') pos++; }

        int peek() { skipWs(); return pos < s.length() ? s.charAt(pos) : -1; }

        Node parseExpr() {
            Node left = parseTerm();
            while (true) {
                int c = peek();
                if (c != '+' && c != '-') return left;
                pos++;
                left = new Bin((char) c, left, parseTerm());
            }
        }

        Node parseTerm() {
            Node left = parseUnary();
            while (true) {
                int c = peek();
                if (c != '*' && c != '/') return left;
                pos++;
                left = new Bin((char) c, left, parseUnary());
            }
        }

        Node parseUnary() {
            if (peek() == '-') {
                pos++;
                return new Neg(parseUnary());
            }
            return parsePrimary();
        }

        Node parsePrimary() {
            int c = peek();
            if (c >= '0' && c <= '9') {
                int st = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
                if (pos < s.length() && s.charAt(pos) == '.') {
                    pos++;
                    int ds = pos;
                    while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
                    if (pos == ds) throw new ParseFail();
                }
                return new Lit(Double.parseDouble(s.substring(st, pos)));
            }
            if (c == '(') {
                pos++;
                Node e = parseExpr();
                if (peek() != ')') throw new ParseFail();
                pos++;
                return e;
            }
            if (isAlpha(c)) {
                String id = ident();
                if (peek() == '(') {
                    if (!FUNCS.contains(id)) throw new ParseFail();
                    pos++;
                    int a = cellRef(ident());
                    if (peek() != ':') throw new ParseFail();
                    pos++;
                    int b = cellRef(ident());
                    if (peek() != ')') throw new ParseFail();
                    pos++;
                    int ca = a % 26, ra = a / 26, cb = b % 26, rb = b / 26;
                    int[] rg = {Math.min(ca, cb), Math.min(ra, rb), Math.max(ca, cb), Math.max(ra, rb)};
                    ranges.add(rg);
                    return new Fn(id, rg[0], rg[1], rg[2], rg[3]);
                }
                int k = cellRef(id);
                refs.set(k);
                return new Ref(k);
            }
            throw new ParseFail();
        }

        String ident() {
            skipWs();
            int st = pos;
            while (pos < s.length() && (isAlpha(s.charAt(pos)) || isDigit(s.charAt(pos)))) pos++;
            if (pos == st) throw new ParseFail();
            return s.substring(st, pos);
        }

        static int cellRef(String id) {
            if (!CELL.matcher(id).matches()) throw new ParseFail();
            return Integer.parseInt(id.substring(1)) * 26 + (id.charAt(0) - 'A');
        }

        static boolean isDigit(int c) { return c >= '0' && c <= '9'; }

        static boolean isAlpha(int c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
    }

    // ---- evaluation ----
    private int[] existingInRange(int c1, int r1, int c2, int r2) {
        long area = (long) (c2 - c1 + 1) * (r2 - r1 + 1);
        if (area <= cells.size()) {
            int[] out = new int[(int) area];
            int n = 0;
            for (int r = r1; r <= r2; r++) {
                for (int c = c1; c <= c2; c++) {
                    int k = r * 26 + c;
                    if (cells.containsKey(k)) out[n++] = k;
                }
            }
            return Arrays.copyOf(out, n);
        }
        int[] out = new int[cells.size()];
        int n = 0;
        for (int k : cells.keySet()) {
            int c = k % 26, r = k / 26;
            if (c >= c1 && c <= c2 && r >= r1 && r <= r2) out[n++] = k;
        }
        out = Arrays.copyOf(out, n);
        Arrays.sort(out);
        return out;
    }

    private int[] depsOf(int key) {
        Cell c = cells.get(key);
        if (c.ast == null) return new int[0];
        BitSet bs = new BitSet();
        for (int k = c.refs.nextSetBit(0); k >= 0; k = c.refs.nextSetBit(k + 1)) {
            Cell d = cells.get(k);
            if (d != null && d.formula) bs.set(k);
        }
        for (int[] rg : c.ranges) {
            for (int k : existingInRange(rg[0], rg[1], rg[2], rg[3])) {
                if (cells.get(k).formula) bs.set(k);
            }
        }
        return bs.stream().toArray();
    }

    private static final class Frame {
        final int node;
        final int[] deps;
        int i;
        Frame(int node, int[] deps) { this.node = node; this.deps = deps; }
    }

    /** Tarjan SCC from root; evaluates cells in dependency order, marking cycles. */
    private void compute(int root) {
        if (results.containsKey(root)) return;
        Map<Integer, Integer> index = new HashMap<>();
        Map<Integer, Integer> low = new HashMap<>();
        Map<Integer, int[]> depMap = new HashMap<>();
        Deque<Integer> stack = new ArrayDeque<>();
        Set<Integer> onStack = new HashSet<>();
        Deque<Frame> call = new ArrayDeque<>();
        int[] counter = {0};
        visit(root, index, low, depMap, stack, onStack, call, counter);
        while (!call.isEmpty()) {
            Frame f = call.peek();
            if (f.i < f.deps.length) {
                int w = f.deps[f.i++];
                if (results.containsKey(w)) continue;
                if (!index.containsKey(w)) {
                    visit(w, index, low, depMap, stack, onStack, call, counter);
                } else if (onStack.contains(w)) {
                    low.put(f.node, Math.min(low.get(f.node), index.get(w)));
                }
            } else {
                call.pop();
                if (low.get(f.node).equals(index.get(f.node))) {
                    List<Integer> scc = new ArrayList<>();
                    int w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (w != f.node);
                    finish(scc, depMap);
                }
                if (!call.isEmpty()) {
                    Frame p = call.peek();
                    low.put(p.node, Math.min(low.get(p.node), low.get(f.node)));
                }
            }
        }
    }

    private void visit(int n, Map<Integer, Integer> index, Map<Integer, Integer> low, Map<Integer, int[]> depMap,
                       Deque<Integer> stack, Set<Integer> onStack, Deque<Frame> call, int[] counter) {
        index.put(n, counter[0]);
        low.put(n, counter[0]);
        counter[0]++;
        stack.push(n);
        onStack.add(n);
        int[] deps = depsOf(n);
        depMap.put(n, deps);
        call.push(new Frame(n, deps));
    }

    private void finish(List<Integer> scc, Map<Integer, int[]> depMap) {
        boolean cyclic = scc.size() > 1;
        int n = scc.get(0);
        if (!cyclic) {
            for (int d : depMap.get(n)) if (d == n) cyclic = true;
        }
        if (cyclic) {
            for (int k : scc) results.put(k, E_CYCLE);
            return;
        }
        for (int d : depMap.get(n)) {
            if (results.get(d) == E_CYCLE) {
                results.put(n, E_CYCLE);
                return;
            }
        }
        Cell c = cells.get(n);
        results.put(n, c.ast == null ? E_PARSE : eval(c.ast));
    }

    private Val valueOf(int key) {
        Cell c = cells.get(key);
        if (c == null) return V_EMPTY;
        return c.formula ? results.get(key) : c.literal;
    }

    private Val operand(Val v) {
        return switch (v.kind) {
            case EMPTY -> Val.num(0);
            case TEXT -> E_VALUE;
            default -> v;
        };
    }

    private Val eval(Node n) {
        Val v = eval0(n);
        if (v.kind == NUM && !Double.isFinite(v.num)) return E_NUM;
        return v;
    }

    private Val eval0(Node n) {
        if (n instanceof Lit l) return Double.isFinite(l.v) ? Val.num(l.v) : E_NUM;
        if (n instanceof Ref r) return operand(valueOf(r.key));
        if (n instanceof Neg g) {
            Val a = eval0(g.a);
            return a.kind == ERR ? a : Val.num(-a.num);
        }
        if (n instanceof Bin b) {
            Val l = eval0(b.l);
            Val r = eval0(b.r);
            if (l.kind == ERR) return l;
            if (r.kind == ERR) return r;
            return switch (b.op) {
                case '+' -> Val.num(l.num + r.num);
                case '-' -> Val.num(l.num - r.num);
                case '*' -> Val.num(l.num * r.num);
                default -> r.num == 0 ? E_DIV0 : Val.num(l.num / r.num);
            };
        }
        Fn f = (Fn) n;
        int[] keys = existingInRange(f.c1, f.r1, f.c2, f.r2);
        double sum = 0, min = 0, max = 0;
        int count = 0;
        for (int k : keys) {
            Val v = valueOf(k);
            if (v.kind == ERR) return v;
            if (v.kind != NUM) continue;
            if (count == 0) { min = v.num; max = v.num; }
            else { min = Math.min(min, v.num); max = Math.max(max, v.num); }
            sum += v.num;
            count++;
        }
        return switch (f.name) {
            case "SUM" -> Val.num(sum);
            case "COUNT" -> Val.num(count);
            case "MIN" -> Val.num(min);
            case "MAX" -> Val.num(max);
            default -> count == 0 ? E_DIV0 : Val.num(sum / count);
        };
    }
}
