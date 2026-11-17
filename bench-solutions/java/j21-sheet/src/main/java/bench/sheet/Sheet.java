package bench.sheet;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

public final class Sheet {
    private static final Pattern CELL = Pattern.compile("[A-Z][1-9][0-9]{0,2}");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final Set<String> FUNCS = Set.of("SUM", "MIN", "MAX", "AVERAGE", "COUNT");

    // ---- values ----
    private sealed interface Val permits Num, Text, Err, Empty {}
    private record Num(double v) implements Val {}
    private record Text(String s) implements Val {}
    private record Err(String code) implements Val {}
    private enum Empty implements Val { INSTANCE }

    private static final Err PARSE = new Err("#PARSE!");
    private static final Err VALUE = new Err("#VALUE!");
    private static final Err DIV0 = new Err("#DIV/0!");
    private static final Err NUM = new Err("#NUM!");
    private static final Err CYCLE = new Err("#CYCLE!");

    // ---- AST ----
    private sealed interface Node permits Lit, Ref, Neg, Bin, Fn {}
    private record Lit(double v) implements Node {}
    private record Ref(int col, int row) implements Node {}
    private record Neg(Node e) implements Node {}
    private record Bin(char op, Node l, Node r) implements Node {}
    private record Fn(String name, int c1, int r1, int c2, int r2) implements Node {}

    private static final class Formula {
        final Node ast; // null on parse error
        final List<Ref> refs = new ArrayList<>();
        final List<Fn> ranges = new ArrayList<>();

        Formula(Node ast) { this.ast = ast; }
    }

    private final Map<String, String> inputs = new HashMap<>();
    private final Map<String, Formula> formulas = new HashMap<>();
    private final Map<String, Val> memo = new HashMap<>();

    public Sheet() {}

    private static void checkName(String cell) {
        if (cell == null || !CELL.matcher(cell).matches()) throw new IllegalArgumentException("bad cell: " + cell);
    }

    private static String name(int col, int row) { return "" + (char) ('A' + col) + row; }

    public void set(String cell, String input) {
        checkName(cell);
        if (input == null) throw new IllegalArgumentException("null input");
        memo.clear();
        if (input.isEmpty()) {
            inputs.remove(cell);
            formulas.remove(cell);
            return;
        }
        inputs.put(cell, input);
        if (input.startsWith("=")) {
            formulas.put(cell, parseFormula(input.substring(1)));
        } else {
            formulas.remove(cell);
        }
    }

    public String get(String cell) {
        checkName(cell);
        Val v = valueOf(cell);
        return display(v, cell);
    }

    private String display(Val v, String cell) {
        if (v instanceof Empty) return "";
        if (v instanceof Text t) return t.s();
        if (v instanceof Err e) return e.code();
        double d = ((Num) v).v();
        if (!Double.isFinite(d)) return NUM.code();
        return fmt(d);
    }

    private static String fmt(double d) {
        if (d == 0) return "0";
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    // ---- evaluation ----
    private Val leaf(String cell) {
        String in = inputs.get(cell);
        if (in == null) return Empty.INSTANCE;
        if (NUMBER.matcher(in).matches()) {
            double d = Double.parseDouble(in);
            return Double.isFinite(d) ? new Num(d) : NUM;
        }
        return new Text(in);
    }

    private Val valueOf(String cell) {
        if (!formulas.containsKey(cell)) return leaf(cell);
        Val m = memo.get(cell);
        if (m == null) {
            compute(cell);
            m = memo.get(cell);
        }
        return m;
    }

    private List<String> deps(String cell) {
        Formula f = formulas.get(cell);
        List<String> out = new ArrayList<>();
        if (f.ast == null) return out;
        for (Ref r : f.refs) {
            String n = name(r.col(), r.row());
            if (formulas.containsKey(n)) out.add(n);
        }
        for (Fn fn : f.ranges) {
            int w = fn.c2() - fn.c1() + 1, h = fn.r2() - fn.r1() + 1;
            if ((long) w * h <= formulas.size()) {
                for (int r = fn.r1(); r <= fn.r2(); r++) {
                    for (int c = fn.c1(); c <= fn.c2(); c++) {
                        String n = name(c, r);
                        if (formulas.containsKey(n)) out.add(n);
                    }
                }
            } else {
                for (String n : formulas.keySet()) {
                    int c = n.charAt(0) - 'A', r = Integer.parseInt(n.substring(1));
                    if (c >= fn.c1() && c <= fn.c2() && r >= fn.r1() && r <= fn.r2()) out.add(n);
                }
            }
        }
        return out;
    }

    /** Iterative Tarjan over the not-yet-memoised formula cells reachable from root. */
    private void compute(String root) {
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Map<String, List<String>> adj = new HashMap<>();
        Deque<String> callStack = new ArrayDeque<>();
        Map<String, Integer> next = new HashMap<>();
        int[] counter = {0};

        callStack.push(root);
        while (!callStack.isEmpty()) {
            String u = callStack.peek();
            if (!index.containsKey(u)) {
                index.put(u, counter[0]);
                low.put(u, counter[0]);
                counter[0]++;
                stack.push(u);
                onStack.add(u);
                adj.put(u, deps(u));
                next.put(u, 0);
            }
            List<String> out = adj.get(u);
            int i = next.get(u);
            boolean descended = false;
            while (i < out.size()) {
                String v = out.get(i++);
                if (memo.containsKey(v)) continue;
                if (!index.containsKey(v)) {
                    next.put(u, i);
                    callStack.push(v);
                    descended = true;
                    break;
                } else if (onStack.contains(v)) {
                    low.put(u, Math.min(low.get(u), index.get(v)));
                }
            }
            if (descended) continue;
            next.put(u, i);
            callStack.pop();
            if (!callStack.isEmpty()) {
                String parent = callStack.peek();
                low.put(parent, Math.min(low.get(parent), low.get(u)));
            }
            if (low.get(u).equals(index.get(u))) {
                List<String> scc = new ArrayList<>();
                String w;
                do {
                    w = stack.pop();
                    onStack.remove(w);
                    scc.add(w);
                } while (!w.equals(u));
                finishScc(scc, adj);
            }
        }
    }

    private void finishScc(List<String> scc, Map<String, List<String>> adj) {
        boolean cyclic = scc.size() > 1 || adj.get(scc.get(0)).contains(scc.get(0));
        if (cyclic) {
            for (String c : scc) memo.put(c, CYCLE);
            return;
        }
        String c = scc.get(0);
        for (String d : adj.get(c)) {
            if (memo.get(d) == CYCLE) {
                memo.put(c, CYCLE);
                return;
            }
        }
        memo.put(c, formulas.get(c).ast == null ? PARSE : toVal(evalNode(formulas.get(c).ast)));
    }

    private static Val toVal(Val v) {
        if (v instanceof Num n && !Double.isFinite(n.v())) return NUM;
        return v;
    }

    private Val cellVal(int col, int row) {
        Val v = valueOf(name(col, row));
        return v;
    }

    private Val evalNode(Node n) {
        if (n instanceof Lit l) return new Num(l.v());
        if (n instanceof Ref r) {
            Val v = cellVal(r.col(), r.row());
            if (v instanceof Empty) return new Num(0);
            if (v instanceof Text) return VALUE;
            return v;
        }
        if (n instanceof Neg g) {
            Val v = evalNode(g.e());
            return v instanceof Num x ? new Num(-x.v()) : v;
        }
        if (n instanceof Bin b) {
            Val l = evalNode(b.l());
            if (l instanceof Err) return l;
            Val r = evalNode(b.r());
            if (r instanceof Err) return r;
            double x = ((Num) l).v(), y = ((Num) r).v();
            double res;
            switch (b.op()) {
                case '+': res = x + y; break;
                case '-': res = x - y; break;
                case '*': res = x * y; break;
                default:
                    if (y == 0) return DIV0;
                    res = x / y;
            }
            return Double.isFinite(res) ? new Num(res) : NUM;
        }
        Fn f = (Fn) n;
        double sum = 0, min = 0, max = 0;
        long count = 0;
        for (int r = f.r1(); r <= f.r2(); r++) {
            for (int c = f.c1(); c <= f.c2(); c++) {
                Val v = cellVal(c, r);
                if (v instanceof Err) return v;
                if (v instanceof Num x) {
                    if (count == 0) { min = x.v(); max = x.v(); }
                    else { min = Math.min(min, x.v()); max = Math.max(max, x.v()); }
                    sum += x.v();
                    count++;
                }
            }
        }
        double res;
        switch (f.name()) {
            case "SUM": res = sum; break;
            case "COUNT": res = count; break;
            case "MIN": res = count == 0 ? 0 : min; break;
            case "MAX": res = count == 0 ? 0 : max; break;
            default:
                if (count == 0) return DIV0;
                res = sum / count;
        }
        return Double.isFinite(res) ? new Num(res) : NUM;
    }

    // ---- parsing ----
    private Formula parseFormula(String s) {
        try {
            Parser p = new Parser(s);
            Node ast = p.parseAll();
            Formula f = new Formula(ast);
            collect(ast, f);
            return f;
        } catch (IllegalArgumentException e) {
            return new Formula(null);
        }
    }

    private static void collect(Node n, Formula f) {
        if (n instanceof Ref r) f.refs.add(r);
        else if (n instanceof Fn fn) f.ranges.add(fn);
        else if (n instanceof Neg g) collect(g.e(), f);
        else if (n instanceof Bin b) { collect(b.l(), f); collect(b.r(), f); }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        Node parseAll() {
            Node n = expr();
            ws();
            if (pos != s.length()) throw new IllegalArgumentException("trailing");
            return n;
        }

        private void ws() { while (pos < s.length() && s.charAt(pos) == ' ') pos++; }

        private boolean eat(char c) {
            ws();
            if (pos < s.length() && s.charAt(pos) == c) { pos++; return true; }
            return false;
        }

        private Node expr() {
            Node v = term();
            while (true) {
                if (eat('+')) v = new Bin('+', v, term());
                else if (eat('-')) v = new Bin('-', v, term());
                else return v;
            }
        }

        private Node term() {
            Node v = factor();
            while (true) {
                if (eat('*')) v = new Bin('*', v, factor());
                else if (eat('/')) v = new Bin('/', v, factor());
                else return v;
            }
        }

        private Node factor() {
            ws();
            if (pos >= s.length()) throw new IllegalArgumentException("end");
            char c = s.charAt(pos);
            if (c == '-') { pos++; return new Neg(factor()); }
            if (c == '(') {
                pos++;
                Node v = expr();
                if (!eat(')')) throw new IllegalArgumentException("expected )");
                return v;
            }
            if (c >= '0' && c <= '9') {
                int st = pos;
                while (pos < s.length() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 128) pos++;
                if (pos < s.length() && s.charAt(pos) == '.') {
                    pos++;
                    int f = pos;
                    while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
                    if (pos == f) throw new IllegalArgumentException("bad number");
                }
                return new Lit(Double.parseDouble(s.substring(st, pos)));
            }
            if (isWordChar(c)) {
                String w = word();
                if (CELL.matcher(w).matches()) return new Ref(w.charAt(0) - 'A', Integer.parseInt(w.substring(1)));
                if (FUNCS.contains(w)) {
                    if (!eat('(')) throw new IllegalArgumentException("expected (");
                    int[] a = cellTok();
                    if (!eat(':')) throw new IllegalArgumentException("expected range");
                    int[] b = cellTok();
                    if (!eat(')')) throw new IllegalArgumentException("expected )");
                    return new Fn(w, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]));
                }
            }
            throw new IllegalArgumentException("unexpected");
        }

        private static boolean isWordChar(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
        }

        private String word() {
            int st = pos;
            while (pos < s.length() && isWordChar(s.charAt(pos))) pos++;
            return s.substring(st, pos);
        }

        private int[] cellTok() {
            ws();
            String w = word();
            if (!CELL.matcher(w).matches()) throw new IllegalArgumentException("bad cell");
            return new int[] {w.charAt(0) - 'A', Integer.parseInt(w.substring(1))};
        }
    }
}
