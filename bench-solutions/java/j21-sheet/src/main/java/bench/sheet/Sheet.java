package bench.sheet;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A small spreadsheet with formulas, ranges, error values and cycle detection. */
public final class Sheet {
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final String VALUE = "#VALUE!";
    private static final String DIV = "#DIV/0!";
    private static final String NUM = "#NUM!";
    private static final String PARSE = "#PARSE!";
    private static final String CYCLE = "#CYCLE!";

    /** A formula result: a number, or an error when err != null. */
    private record Val(double n, String err) {
        static final Val ZERO = new Val(0, null);

        static Val err(String e) {
            return new Val(0, e);
        }

        static Val num(double d) {
            return Double.isFinite(d) ? new Val(d, null) : err(NUM);
        }
    }

    private sealed interface Node permits Lit, Ref, Neg, Bin, Fn {}
    private record Lit(double v) implements Node {}
    private record Ref(int key) implements Node {}
    private record Neg(Node e) implements Node {}
    private record Bin(char op, Node l, Node r) implements Node {}
    private record Fn(String name, int c1, int r1, int c2, int r2) implements Node {}

    private static final int TEXT = 0, NUMBER_KIND = 1, FORMULA = 2;

    private static final class Cell {
        final int key;
        final int col;
        final int row;
        final int kind;
        final String text;
        final double num;
        final Node ast; // null on parse error
        final List<int[]> rects; // dependency rectangles {c1, r1, c2, r2}

        Cell(int key, int kind, String text, double num, Node ast, List<int[]> rects) {
            this.key = key;
            this.col = key / 1000;
            this.row = key % 1000;
            this.kind = kind;
            this.text = text;
            this.num = num;
            this.ast = ast;
            this.rects = rects;
        }
    }

    private final Map<Integer, Cell> cells = new HashMap<>();
    private final Map<Integer, Cell> formulas = new HashMap<>();
    private final Map<Integer, Val> cache = new HashMap<>();

    public Sheet() {
    }

    // ---- names ----

    private static boolean validRef(String id) {
        int len = id.length();
        if (len < 2 || len > 4) {
            return false;
        }
        char c = id.charAt(0);
        if (c < 'A' || c > 'Z') {
            return false;
        }
        char d = id.charAt(1);
        if (d < '1' || d > '9') {
            return false;
        }
        for (int i = 2; i < len; i++) {
            char x = id.charAt(i);
            if (x < '0' || x > '9') {
                return false;
            }
        }
        return true;
    }

    private static int keyOf(String id) {
        return (id.charAt(0) - 'A') * 1000 + Integer.parseInt(id.substring(1));
    }

    private static int keyOrThrow(String cell) {
        if (cell == null || !validRef(cell)) {
            throw new IllegalArgumentException("bad cell name: " + cell);
        }
        return keyOf(cell);
    }

    // ---- public API ----

    public void set(String cell, String input) {
        int key = keyOrThrow(cell);
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        cache.clear();
        formulas.remove(key);
        if (input.isEmpty()) {
            cells.remove(key);
            return;
        }
        Cell c;
        if (input.charAt(0) == '=') {
            Parser p = new Parser(input.substring(1));
            Node ast = null;
            try {
                ast = p.parse();
            } catch (ParseFail e) {
                ast = null;
            }
            c = new Cell(key, FORMULA, input, 0, ast, ast == null ? List.of() : p.rects);
            formulas.put(key, c);
        } else if (NUMBER.matcher(input).matches()) {
            c = new Cell(key, NUMBER_KIND, input, Double.parseDouble(input), null, List.of());
        } else {
            c = new Cell(key, TEXT, input, 0, null, List.of());
        }
        cells.put(key, c);
    }

    public String get(String cell) {
        int key = keyOrThrow(cell);
        Cell c = cells.get(key);
        if (c == null) {
            return "";
        }
        switch (c.kind) {
            case TEXT:
                return c.text;
            case NUMBER_KIND:
                return display(c.num);
            default: {
                resolve(c);
                Val v = cache.get(key);
                return v.err() != null ? v.err() : display(v.n());
            }
        }
    }

    private static String display(double v) {
        if (v == 0) {
            return "0";
        }
        if (!Double.isFinite(v)) {
            return NUM;
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

    private static final class Parser {
        final String s;
        int p;
        final List<int[]> rects = new ArrayList<>();

        Parser(String s) {
            this.s = s;
        }

        Node parse() {
            Node n = expr();
            skip();
            if (p != s.length()) {
                throw new ParseFail();
            }
            return n;
        }

        void skip() {
            while (p < s.length() && s.charAt(p) == ' ') {
                p++;
            }
        }

        Node expr() {
            Node l = term();
            while (true) {
                skip();
                if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) {
                    char op = s.charAt(p++);
                    Node r = term();
                    l = new Bin(op, l, r);
                } else {
                    return l;
                }
            }
        }

        Node term() {
            Node l = unary();
            while (true) {
                skip();
                if (p < s.length() && (s.charAt(p) == '*' || s.charAt(p) == '/')) {
                    char op = s.charAt(p++);
                    Node r = unary();
                    l = new Bin(op, l, r);
                } else {
                    return l;
                }
            }
        }

        Node unary() {
            skip();
            if (p < s.length() && s.charAt(p) == '-') {
                p++;
                return new Neg(unary());
            }
            return primary();
        }

        static boolean digit(char c) {
            return c >= '0' && c <= '9';
        }

        static boolean letter(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }

        String ident() {
            int st = p;
            while (p < s.length() && (letter(s.charAt(p)) || digit(s.charAt(p)))) {
                p++;
            }
            return s.substring(st, p);
        }

        Node primary() {
            skip();
            if (p >= s.length()) {
                throw new ParseFail();
            }
            char c = s.charAt(p);
            if (digit(c)) {
                int st = p;
                while (p < s.length() && digit(s.charAt(p))) {
                    p++;
                }
                if (p < s.length() && s.charAt(p) == '.') {
                    p++;
                    if (p >= s.length() || !digit(s.charAt(p))) {
                        throw new ParseFail();
                    }
                    while (p < s.length() && digit(s.charAt(p))) {
                        p++;
                    }
                }
                return new Lit(Double.parseDouble(s.substring(st, p)));
            }
            if (c == '(') {
                p++;
                Node e = expr();
                skip();
                if (p >= s.length() || s.charAt(p) != ')') {
                    throw new ParseFail();
                }
                p++;
                return e;
            }
            if (letter(c)) {
                String id = ident();
                skip();
                if (p < s.length() && s.charAt(p) == '(') {
                    if (!(id.equals("SUM") || id.equals("MIN") || id.equals("MAX") || id.equals("AVERAGE")
                            || id.equals("COUNT"))) {
                        throw new ParseFail();
                    }
                    p++;
                    skip();
                    int a = refToken();
                    skip();
                    if (p >= s.length() || s.charAt(p) != ':') {
                        throw new ParseFail();
                    }
                    p++;
                    skip();
                    int b = refToken();
                    skip();
                    if (p >= s.length() || s.charAt(p) != ')') {
                        throw new ParseFail();
                    }
                    p++;
                    int c1 = Math.min(a / 1000, b / 1000);
                    int c2 = Math.max(a / 1000, b / 1000);
                    int r1 = Math.min(a % 1000, b % 1000);
                    int r2 = Math.max(a % 1000, b % 1000);
                    rects.add(new int[] {c1, r1, c2, r2});
                    return new Fn(id, c1, r1, c2, r2);
                }
                if (!validRef(id)) {
                    throw new ParseFail();
                }
                int key = keyOf(id);
                rects.add(new int[] {key / 1000, key % 1000, key / 1000, key % 1000});
                return new Ref(key);
            }
            throw new ParseFail();
        }

        int refToken() {
            if (p >= s.length() || !letter(s.charAt(p))) {
                throw new ParseFail();
            }
            String id = ident();
            if (!validRef(id)) {
                throw new ParseFail();
            }
            return keyOf(id);
        }
    }

    // ---- dependency resolution (iterative Tarjan SCC) ----

    private static final class St {
        final Cell cell;
        final int index;
        int low;
        boolean on;
        int i;
        final List<Cell> deps;

        St(Cell cell, int index, List<Cell> deps) {
            this.cell = cell;
            this.index = index;
            this.low = index;
            this.deps = deps;
        }
    }

    private List<Cell> depsOf(Cell c) {
        List<Cell> out = new ArrayList<>();
        for (int[] r : c.rects) {
            long area = (long) (r[2] - r[0] + 1) * (r[3] - r[1] + 1);
            if (area <= formulas.size()) {
                for (int col = r[0]; col <= r[2]; col++) {
                    for (int row = r[1]; row <= r[3]; row++) {
                        Cell d = formulas.get(col * 1000 + row);
                        if (d != null) {
                            out.add(d);
                        }
                    }
                }
            } else {
                for (Cell d : formulas.values()) {
                    if (d.col >= r[0] && d.col <= r[2] && d.row >= r[1] && d.row <= r[3]) {
                        out.add(d);
                    }
                }
            }
        }
        return out;
    }

    private void resolve(Cell root) {
        if (cache.containsKey(root.key)) {
            return;
        }
        Map<Cell, St> state = new HashMap<>();
        Deque<St> call = new ArrayDeque<>();
        Deque<St> scc = new ArrayDeque<>();
        int counter = 0;
        St rs = new St(root, counter++, depsOf(root));
        rs.on = true;
        state.put(root, rs);
        call.push(rs);
        scc.push(rs);
        while (!call.isEmpty()) {
            St s = call.peek();
            if (s.i < s.deps.size()) {
                Cell d = s.deps.get(s.i++);
                if (cache.containsKey(d.key)) {
                    continue;
                }
                St ds = state.get(d);
                if (ds == null) {
                    ds = new St(d, counter++, depsOf(d));
                    ds.on = true;
                    state.put(d, ds);
                    call.push(ds);
                    scc.push(ds);
                } else if (ds.on) {
                    s.low = Math.min(s.low, ds.index);
                }
            } else {
                call.pop();
                if (!call.isEmpty()) {
                    St parent = call.peek();
                    parent.low = Math.min(parent.low, s.low);
                }
                if (s.low == s.index) {
                    List<St> members = new ArrayList<>();
                    St m;
                    do {
                        m = scc.pop();
                        m.on = false;
                        members.add(m);
                    } while (m != s);
                    boolean cyclic = members.size() > 1;
                    if (!cyclic) {
                        for (Cell d : s.deps) {
                            if (d == s.cell) {
                                cyclic = true;
                                break;
                            }
                        }
                    }
                    if (cyclic) {
                        for (St x : members) {
                            cache.put(x.cell.key, Val.err(CYCLE));
                        }
                    } else {
                        boolean dependsOnCycle = false;
                        for (Cell d : s.deps) {
                            Val v = cache.get(d.key);
                            if (v != null && CYCLE.equals(v.err())) {
                                dependsOnCycle = true;
                                break;
                            }
                        }
                        cache.put(s.cell.key, dependsOnCycle ? Val.err(CYCLE) : evalCell(s.cell));
                    }
                }
            }
        }
    }

    // ---- evaluation ----

    private Val evalCell(Cell c) {
        if (c.ast == null) {
            return Val.err(PARSE);
        }
        return eval(c.ast);
    }

    private Val cellValue(int key) {
        Cell c = cells.get(key);
        if (c == null) {
            return Val.ZERO;
        }
        switch (c.kind) {
            case TEXT:
                return Val.err(VALUE);
            case NUMBER_KIND:
                return new Val(c.num, null);
            default:
                return cache.get(key);
        }
    }

    private Val eval(Node node) {
        if (node instanceof Lit l) {
            return new Val(l.v(), null);
        } else if (node instanceof Ref r) {
            return cellValue(r.key());
        } else if (node instanceof Neg n) {
            Val v = eval(n.e());
            if (v.err() != null) {
                return v;
            }
            return Val.num(-v.n());
        } else if (node instanceof Bin b) {
            Val l = eval(b.l());
            Val r = eval(b.r());
            if (l.err() != null) {
                return l;
            }
            if (r.err() != null) {
                return r;
            }
            switch (b.op()) {
                case '+':
                    return Val.num(l.n() + r.n());
                case '-':
                    return Val.num(l.n() - r.n());
                case '*':
                    return Val.num(l.n() * r.n());
                default:
                    if (r.n() == 0) {
                        return Val.err(DIV);
                    }
                    return Val.num(l.n() / r.n());
            }
        }
        return evalFn((Fn) node);
    }

    private Val evalFn(Fn f) {
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long count = 0;
        for (int row = f.r1(); row <= f.r2(); row++) {
            for (int col = f.c1(); col <= f.c2(); col++) {
                int key = col * 1000 + row;
                Cell c = cells.get(key);
                if (c == null || c.kind == TEXT) {
                    continue;
                }
                double v;
                if (c.kind == NUMBER_KIND) {
                    v = c.num;
                } else {
                    Val cv = cache.get(key);
                    if (cv.err() != null) {
                        return cv;
                    }
                    v = cv.n();
                }
                count++;
                sum += v;
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
            }
        }
        switch (f.name()) {
            case "SUM":
                return Val.num(sum);
            case "COUNT":
                return Val.num(count);
            case "AVERAGE":
                return count == 0 ? Val.err(DIV) : Val.num(sum / count);
            case "MIN":
                return count == 0 ? Val.ZERO : Val.num(min);
            default:
                return count == 0 ? Val.ZERO : Val.num(max);
        }
    }
}
