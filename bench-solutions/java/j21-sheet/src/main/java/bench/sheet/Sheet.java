package bench.sheet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** A small spreadsheet with formulas, ranges and cycle detection. */
public final class Sheet {
    private static final int COLS = 26;
    private static final int ROWS = 999;
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final String PARSE = "#PARSE!";
    private static final String VALUE = "#VALUE!";
    private static final String DIV0 = "#DIV/0!";
    private static final String NUM = "#NUM!";
    private static final String CYCLE = "#CYCLE!";

    private enum Kind { NUMBER, TEXT, FORMULA }

    // ---- formula AST ----
    private sealed interface Node permits Num, Ref, Neg, Bin, Fn {}
    private record Num(double v) implements Node {}
    private record Ref(int idx) implements Node {}
    private record Neg(Node e) implements Node {}
    private record Bin(char op, Node l, Node r) implements Node {}
    private record Fn(String name, int c1, int r1, int c2, int r2) implements Node {}

    private static final class Cell {
        Kind kind;
        String raw;
        double num;       // NUMBER
        Node ast;         // FORMULA (null if parse error)
        int[] deps = new int[0];  // indexes of formula cells (valid parse) referenced
        // evaluation results for FORMULA
        double val;
        String err;
        String display;
    }

    private final Cell[] cells = new Cell[COLS * ROWS];
    private boolean dirty = true;

    public Sheet() {
    }

    private static int index(String cell) {
        if (cell == null || cell.length() < 2 || cell.length() > 4) {
            throw new IllegalArgumentException("bad cell: " + cell);
        }
        char c = cell.charAt(0);
        if (c < 'A' || c > 'Z') {
            throw new IllegalArgumentException("bad cell: " + cell);
        }
        int row = parseRow(cell, 1);
        if (row < 0) {
            throw new IllegalArgumentException("bad cell: " + cell);
        }
        return idx(c - 'A', row);
    }

    /** Parses digits from s[from..] as a row 1..999 with no leading zero; -1 if invalid. */
    private static int parseRow(String s, int from) {
        int len = s.length() - from;
        if (len < 1 || len > 3 || s.charAt(from) == '0') {
            return -1;
        }
        int v = 0;
        for (int i = from; i < s.length(); i++) {
            char d = s.charAt(i);
            if (d < '0' || d > '9') {
                return -1;
            }
            v = v * 10 + (d - '0');
        }
        return v >= 1 && v <= ROWS ? v : -1;
    }

    private static int idx(int col, int row) {
        return (row - 1) * COLS + col;
    }

    public void set(String cell, String input) {
        int i = index(cell);
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        dirty = true;
        if (input.isEmpty()) {
            cells[i] = null;
            return;
        }
        Cell c = new Cell();
        c.raw = input;
        if (input.charAt(0) == '=') {
            c.kind = Kind.FORMULA;
            try {
                List<Integer> refs = new ArrayList<>();
                c.ast = new FormulaParser(input, refs).parse();
                c.deps = refs.stream().mapToInt(Integer::intValue).toArray();
            } catch (ParseFail f) {
                c.ast = null;
            }
        } else if (NUMBER.matcher(input).matches()) {
            c.kind = Kind.NUMBER;
            c.num = Double.parseDouble(input);
        } else {
            c.kind = Kind.TEXT;
        }
        cells[i] = c;
    }

    public String get(String cell) {
        int i = index(cell);
        if (dirty) {
            recompute();
        }
        Cell c = cells[i];
        if (c == null) {
            return "";
        }
        switch (c.kind) {
            case TEXT:
                return c.raw;
            case NUMBER:
                return format(c.num);
            default:
                return c.display;
        }
    }

    private static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return NUM;
        }
        if (v == 0) {
            return "0";
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    // ---- parsing ----
    private static final class ParseFail extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseFail() {
            super(null, null, false, false);
        }
    }

    private static final class FormulaParser {
        private final String s;
        private final List<Integer> refs;
        private int pos = 1;

        FormulaParser(String s, List<Integer> refs) {
            this.s = s;
            this.refs = refs;
        }

        Node parse() {
            Node n = expr();
            skip();
            if (pos != s.length()) {
                throw new ParseFail();
            }
            return n;
        }

        private void skip() {
            while (pos < s.length() && s.charAt(pos) == ' ') {
                pos++;
            }
        }

        private int peek() {
            skip();
            return pos < s.length() ? s.charAt(pos) : -1;
        }

        private Node expr() {
            Node l = term();
            while (true) {
                int c = peek();
                if (c == '+' || c == '-') {
                    pos++;
                    l = new Bin((char) c, l, term());
                } else {
                    return l;
                }
            }
        }

        private Node term() {
            Node l = unary();
            while (true) {
                int c = peek();
                if (c == '*' || c == '/') {
                    pos++;
                    l = new Bin((char) c, l, unary());
                } else {
                    return l;
                }
            }
        }

        private Node unary() {
            if (peek() == '-') {
                pos++;
                return new Neg(unary());
            }
            return primary();
        }

        private Node primary() {
            int c = peek();
            if (c == '(') {
                pos++;
                Node e = expr();
                if (peek() != ')') {
                    throw new ParseFail();
                }
                pos++;
                return e;
            }
            if (c >= '0' && c <= '9') {
                int st = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos < s.length() && s.charAt(pos) == '.') {
                    pos++;
                    int ds = pos;
                    while (pos < s.length() && isDigit(s.charAt(pos))) {
                        pos++;
                    }
                    if (pos == ds) {
                        throw new ParseFail();
                    }
                }
                return new Num(Double.parseDouble(s.substring(st, pos)));
            }
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
                String id = ident();
                if (peek() == '(') {
                    return function(id);
                }
                int[] cr = cellRef(id);
                refs.add(idx(cr[0], cr[1]));
                return new Ref(idx(cr[0], cr[1]));
            }
            throw new ParseFail();
        }

        private static boolean isDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }

        private String ident() {
            int st = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                pos++;
            }
            return s.substring(st, pos);
        }

        /** Returns {col, row} (row 1-based) or fails. */
        private static int[] cellRef(String id) {
            if (id.length() < 2) {
                throw new ParseFail();
            }
            char c = id.charAt(0);
            if (c < 'A' || c > 'Z') {
                throw new ParseFail();
            }
            int row = parseRow(id, 1);
            if (row < 0) {
                throw new ParseFail();
            }
            return new int[] {c - 'A', row};
        }

        private Node function(String name) {
            switch (name) {
                case "SUM", "MIN", "MAX", "AVERAGE", "COUNT":
                    break;
                default:
                    throw new ParseFail();
            }
            pos++; // '('
            skip();
            int[] a = cellRef(ident());
            if (peek() != ':') {
                throw new ParseFail();
            }
            pos++;
            skip();
            int[] b = cellRef(ident());
            if (peek() != ')') {
                throw new ParseFail();
            }
            pos++;
            int c1 = Math.min(a[0], b[0]);
            int c2 = Math.max(a[0], b[0]);
            int r1 = Math.min(a[1], b[1]);
            int r2 = Math.max(a[1], b[1]);
            for (int r = r1; r <= r2; r++) {
                for (int cc = c1; cc <= c2; cc++) {
                    refs.add(idx(cc, r));
                }
            }
            return new Fn(name, c1, r1, c2, r2);
        }
    }

    // ---- evaluation ----
    private void recompute() {
        int total = cells.length;
        // dependency edges among formula cells with a valid parse
        boolean[] isNode = new boolean[total];
        for (int i = 0; i < total; i++) {
            Cell c = cells[i];
            if (c != null && c.kind == Kind.FORMULA) {
                if (c.ast == null) {
                    c.err = PARSE;
                    c.val = 0;
                    c.display = PARSE;
                } else {
                    isNode[i] = true;
                }
            }
        }
        int[] index = new int[total];
        int[] low = new int[total];
        boolean[] onStack = new boolean[total];
        boolean[] done = new boolean[total];
        java.util.Arrays.fill(index, -1);
        int[] cursor = new int[total];
        int counter = 0;
        int[] tarjan = new int[total];
        int tsp = 0;
        int[] call = new int[total];
        for (int root = 0; root < total; root++) {
            if (!isNode[root] || index[root] != -1) {
                continue;
            }
            int csp = 0;
            call[csp++] = root;
            index[root] = low[root] = counter++;
            tarjan[tsp++] = root;
            onStack[root] = true;
            while (csp > 0) {
                int v = call[csp - 1];
                int[] deps = cells[v].deps;
                if (cursor[v] < deps.length) {
                    int w = deps[cursor[v]++];
                    if (!isNode[w]) {
                        continue;
                    }
                    if (index[w] == -1) {
                        index[w] = low[w] = counter++;
                        tarjan[tsp++] = w;
                        onStack[w] = true;
                        call[csp++] = w;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                } else {
                    csp--;
                    if (csp > 0) {
                        int parent = call[csp - 1];
                        low[parent] = Math.min(low[parent], low[v]);
                    }
                    if (low[v] == index[v]) {
                        int start = tsp;
                        int w;
                        do {
                            w = tarjan[--tsp];
                            onStack[w] = false;
                        } while (w != v);
                        evaluateScc(tarjan, tsp, start, done);
                    }
                }
            }
        }
        dirty = false;
    }

    private void evaluateScc(int[] members, int from, int to, boolean[] done) {
        boolean cyclic = to - from > 1;
        if (!cyclic) {
            int v = members[from];
            for (int d : cells[v].deps) {
                if (d == v) {
                    cyclic = true;
                    break;
                }
            }
        }
        if (!cyclic) {
            // tainted if any dependency is a cycle error
            int v = members[from];
            for (int d : cells[v].deps) {
                Cell dc = cells[d];
                if (dc != null && dc.kind == Kind.FORMULA && CYCLE.equals(dc.err)) {
                    cyclic = true;
                    break;
                }
            }
        }
        for (int k = from; k < to; k++) {
            Cell c = cells[members[k]];
            if (cyclic) {
                c.err = CYCLE;
                c.val = 0;
                c.display = CYCLE;
            } else {
                Evaluator ev = new Evaluator();
                double v = ev.eval(c.ast);
                if (ev.err != null) {
                    c.err = ev.err;
                    c.val = 0;
                    c.display = ev.err;
                } else {
                    c.err = null;
                    c.val = v;
                    c.display = format(v);
                }
            }
            done[members[k]] = true;
        }
    }

    private final class Evaluator {
        String err;

        double eval(Node n) {
            if (n instanceof Num num) {
                return num.v();
            } else if (n instanceof Ref r) {
                return ref(r.idx());
            } else if (n instanceof Neg g) {
                double v = eval(g.e());
                if (err != null) {
                    return 0;
                }
                return -v;
            } else if (n instanceof Bin b) {
                double l = eval(b.l());
                if (err != null) {
                    return 0;
                }
                double r = eval(b.r());
                if (err != null) {
                    return 0;
                }
                double res;
                switch (b.op()) {
                    case '+' -> res = l + r;
                    case '-' -> res = l - r;
                    case '*' -> res = l * r;
                    default -> {
                        if (r == 0) {
                            err = DIV0;
                            return 0;
                        }
                        res = l / r;
                    }
                }
                return finite(res);
            }
            return function((Fn) n);
        }

        private double finite(double v) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                err = NUM;
                return 0;
            }
            return v;
        }

        private double ref(int i) {
            Cell c = cells[i];
            if (c == null) {
                return 0;
            }
            switch (c.kind) {
                case NUMBER:
                    return c.num;
                case TEXT:
                    err = VALUE;
                    return 0;
                default:
                    if (c.err != null) {
                        err = c.err;
                        return 0;
                    }
                    return c.val;
            }
        }

        private double function(Fn f) {
            double sum = 0;
            double min = 0;
            double max = 0;
            long count = 0;
            for (int r = f.r1(); r <= f.r2(); r++) {
                for (int col = f.c1(); col <= f.c2(); col++) {
                    Cell c = cells[idx(col, r)];
                    if (c == null || c.kind == Kind.TEXT) {
                        continue;
                    }
                    double v;
                    if (c.kind == Kind.NUMBER) {
                        v = c.num;
                    } else if (c.err != null) {
                        err = c.err;
                        return 0;
                    } else {
                        v = c.val;
                    }
                    if (count == 0) {
                        min = v;
                        max = v;
                    } else {
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                    }
                    sum += v;
                    count++;
                }
            }
            switch (f.name()) {
                case "SUM":
                    return finite(sum);
                case "COUNT":
                    return count;
                case "MIN":
                    return finite(min);
                case "MAX":
                    return finite(max);
                default:
                    if (count == 0) {
                        err = DIV0;
                        return 0;
                    }
                    return finite(sum / count);
            }
        }
    }
}
