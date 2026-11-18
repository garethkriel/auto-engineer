package bench.sheet;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A small spreadsheet evaluator with formulas, ranges, error values and cycle detection. */
public final class Sheet {

    // ---------- values ----------
    private sealed interface Val permits Num, Txt, Err, Empty { }

    private record Num(double v) implements Val { }

    private record Txt(String s) implements Val { }

    private record Err(String code) implements Val { }

    private record Empty() implements Val { }

    private static final Empty EMPTY = new Empty();
    private static final Err PARSE = new Err("#PARSE!");
    private static final Err VALUE = new Err("#VALUE!");
    private static final Err DIV0 = new Err("#DIV/0!");
    private static final Err NUM = new Err("#NUM!");
    private static final Err CYCLE = new Err("#CYCLE!");

    // ---------- AST ----------
    private sealed interface Node permits Lit, Ref, Neg, Bin, Fn { }

    private record Lit(double v) implements Node { }

    private record Ref(int key) implements Node { }

    private record Neg(Node a) implements Node { }

    private record Bin(char op, Node l, Node r) implements Node { }

    private record Fn(String name, int r1, int c1, int r2, int c2) implements Node { }

    private static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException() {
            super(null, null, false, false);
        }
    }

    // ---------- cells ----------
    private static final class Cell {
        final String input;
        final Val direct;      // for number / text cells
        final boolean formula;
        final Node ast;        // null when parse failed

        Cell(String input, Val direct, boolean formula, Node ast) {
            this.input = input;
            this.direct = direct;
            this.formula = formula;
            this.ast = ast;
        }
    }

    private final Map<Integer, Cell> cells = new HashMap<>();
    private final Set<Integer> formulaKeys = new LinkedHashSet<>();
    private final Map<Integer, Val> done = new HashMap<>();

    public Sheet() { }

    private static int key(int row, int col) {
        return row * 26 + col;
    }

    private static int parseCellName(String name) {
        if (name == null) {
            return -1;
        }
        int n = name.length();
        if (n < 2 || n > 4) {
            return -1;
        }
        char c = name.charAt(0);
        if (c < 'A' || c > 'Z') {
            return -1;
        }
        char d = name.charAt(1);
        if (d < '1' || d > '9') {
            return -1;
        }
        int row = d - '0';
        for (int i = 2; i < n; i++) {
            char x = name.charAt(i);
            if (x < '0' || x > '9') {
                return -1;
            }
            row = row * 10 + (x - '0');
        }
        if (row > 999) {
            return -1;
        }
        return key(row, c - 'A');
    }

    private static int requireCell(String name) {
        int k = parseCellName(name);
        if (k < 0) {
            throw new IllegalArgumentException("invalid cell name: " + name);
        }
        return k;
    }

    public synchronized void set(String cell, String input) {
        int k = requireCell(cell);
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        done.clear();
        formulaKeys.remove(k);
        if (input.isEmpty()) {
            cells.remove(k);
            return;
        }
        if (input.charAt(0) == '=') {
            Node ast;
            try {
                ast = new Parser(input.substring(1)).parseAll();
            } catch (ParseException | StackOverflowError e) {
                ast = null;
            }
            cells.put(k, new Cell(input, null, true, ast));
            formulaKeys.add(k);
        } else if (isNumber(input)) {
            double v = Double.parseDouble(input);
            Val val = Double.isFinite(v) ? new Num(v) : NUM;
            cells.put(k, new Cell(input, val, false, null));
        } else {
            cells.put(k, new Cell(input, new Txt(input), false, null));
        }
    }

    private static boolean isNumber(String s) {
        int i = 0;
        int n = s.length();
        if (i < n && s.charAt(i) == '-') {
            i++;
        }
        int ds = i;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        if (i == ds) {
            return false;
        }
        if (i == n) {
            return true;
        }
        if (s.charAt(i) != '.') {
            return false;
        }
        i++;
        int fs = i;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i > fs && i == n;
    }

    public synchronized String get(String cell) {
        int k = requireCell(cell);
        Cell c = cells.get(k);
        if (c == null) {
            return "";
        }
        Val v = c.formula ? resolve(k) : c.direct;
        return display(v);
    }

    private static String display(Val v) {
        return switch (v) {
            case Empty e -> "";
            case Txt t -> t.s();
            case Err e -> e.code();
            case Num n -> {
                double d = n.v();
                if (d == 0) {
                    yield "0";
                }
                yield BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            }
        };
    }

    // ---------- parsing ----------
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Node parseAll() {
            Node n = expr();
            skip();
            if (pos != s.length()) {
                throw new ParseException();
            }
            return n;
        }

        private void skip() {
            while (pos < s.length() && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) {
                pos++;
            }
        }

        private Node expr() {
            Node l = term();
            while (true) {
                skip();
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    char op = s.charAt(pos++);
                    Node r = term();
                    l = new Bin(op, l, r);
                } else {
                    return l;
                }
            }
        }

        private Node term() {
            Node l = unary();
            while (true) {
                skip();
                if (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                    char op = s.charAt(pos++);
                    Node r = unary();
                    l = new Bin(op, l, r);
                } else {
                    return l;
                }
            }
        }

        private Node unary() {
            skip();
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
                return new Neg(unary());
            }
            return primary();
        }

        private Node primary() {
            skip();
            if (pos >= s.length()) {
                throw new ParseException();
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Node n = expr();
                skip();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new ParseException();
                }
                pos++;
                return n;
            }
            if (c >= '0' && c <= '9') {
                int st = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos < s.length() && s.charAt(pos) == '.') {
                    pos++;
                    int fs = pos;
                    while (pos < s.length() && isDigit(s.charAt(pos))) {
                        pos++;
                    }
                    if (pos == fs) {
                        throw new ParseException();
                    }
                }
                return new Lit(Double.parseDouble(s.substring(st, pos)));
            }
            if (isIdentChar(c)) {
                String id = ident();
                int k = parseCellName(id);
                if (k >= 0) {
                    return new Ref(k);
                }
                if (id.equals("SUM") || id.equals("MIN") || id.equals("MAX")
                        || id.equals("AVERAGE") || id.equals("COUNT")) {
                    skip();
                    if (pos >= s.length() || s.charAt(pos) != '(') {
                        throw new ParseException();
                    }
                    pos++;
                    int a = rangeRef();
                    skip();
                    if (pos >= s.length() || s.charAt(pos) != ':') {
                        throw new ParseException();
                    }
                    pos++;
                    int b = rangeRef();
                    skip();
                    if (pos >= s.length() || s.charAt(pos) != ')') {
                        throw new ParseException();
                    }
                    pos++;
                    int r1 = a / 26;
                    int c1 = a % 26;
                    int r2 = b / 26;
                    int c2 = b % 26;
                    return new Fn(id, Math.min(r1, r2), Math.min(c1, c2), Math.max(r1, r2), Math.max(c1, c2));
                }
            }
            throw new ParseException();
        }

        private int rangeRef() {
            skip();
            if (pos >= s.length() || !isIdentChar(s.charAt(pos))) {
                throw new ParseException();
            }
            int k = parseCellName(ident());
            if (k < 0) {
                throw new ParseException();
            }
            return k;
        }

        private String ident() {
            int st = pos;
            while (pos < s.length() && isIdentChar(s.charAt(pos))) {
                pos++;
            }
            return s.substring(st, pos);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isIdentChar(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
        }
    }

    // ---------- dependency analysis and evaluation ----------
    private void collectDeps(Node n, List<Integer> out) {
        switch (n) {
            case Lit l -> { }
            case Ref r -> {
                Cell c = cells.get(r.key());
                if (c != null && c.formula) {
                    out.add(r.key());
                }
            }
            case Neg g -> collectDeps(g.a(), out);
            case Bin b -> {
                collectDeps(b.l(), out);
                collectDeps(b.r(), out);
            }
            case Fn f -> {
                long area = (long) (f.r2() - f.r1() + 1) * (f.c2() - f.c1() + 1);
                if (area <= formulaKeys.size()) {
                    for (int r = f.r1(); r <= f.r2(); r++) {
                        for (int c = f.c1(); c <= f.c2(); c++) {
                            int k = key(r, c);
                            Cell cell = cells.get(k);
                            if (cell != null && cell.formula) {
                                out.add(k);
                            }
                        }
                    }
                } else {
                    for (int k : formulaKeys) {
                        int r = k / 26;
                        int c = k % 26;
                        if (r >= f.r1() && r <= f.r2() && c >= f.c1() && c <= f.c2()) {
                            out.add(k);
                        }
                    }
                }
            }
        }
    }

    private static final class Frame {
        final int key;
        final int[] deps;
        int pos;
        boolean tainted;
        boolean selfLoop;

        Frame(int key, int[] deps) {
            this.key = key;
            this.deps = deps;
        }
    }

    private int[] depsOf(int k) {
        Cell c = cells.get(k);
        if (c == null || c.ast == null) {
            return new int[0];
        }
        List<Integer> l = new ArrayList<>();
        collectDeps(c.ast, l);
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    /** Returns the finalized value of formula cell {@code root}, computing it if necessary. */
    private Val resolve(int root) {
        Val have = done.get(root);
        if (have != null) {
            return have;
        }
        Map<Integer, Integer> idx = new HashMap<>();
        Map<Integer, Integer> low = new HashMap<>();
        Set<Integer> onStack = new LinkedHashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        Deque<Frame> frames = new ArrayDeque<>();
        int[] counter = {0};
        open(root, idx, low, onStack, stack, frames, counter);
        while (!frames.isEmpty()) {
            Frame f = frames.peek();
            if (f.pos < f.deps.length) {
                int d = f.deps[f.pos++];
                Val dv = done.get(d);
                if (dv != null) {
                    if (dv == CYCLE) {
                        f.tainted = true;
                    }
                } else if (idx.containsKey(d)) {
                    if (onStack.contains(d)) {
                        low.merge(f.key, idx.get(d), Math::min);
                        if (d == f.key) {
                            f.selfLoop = true;
                        }
                    }
                } else {
                    open(d, idx, low, onStack, stack, frames, counter);
                }
                continue;
            }
            frames.pop();
            int k = f.key;
            if (low.get(k).equals(idx.get(k))) {
                List<Integer> members = new ArrayList<>();
                int m;
                do {
                    m = stack.pop();
                    onStack.remove(m);
                    members.add(m);
                } while (m != k);
                if (members.size() > 1 || f.selfLoop || f.tainted) {
                    for (int x : members) {
                        done.put(x, CYCLE);
                    }
                } else {
                    done.put(k, evalCell(k));
                }
                Frame parent = frames.peek();
                if (parent != null && done.get(k) == CYCLE) {
                    parent.tainted = true;
                }
            } else {
                Frame parent = frames.peek();
                if (parent != null) {
                    low.merge(parent.key, low.get(k), Math::min);
                    if (f.tainted) {
                        parent.tainted = true;
                    }
                }
            }
        }
        return done.get(root);
    }

    private void open(int k, Map<Integer, Integer> idx, Map<Integer, Integer> low, Set<Integer> onStack,
                      Deque<Integer> stack, Deque<Frame> frames, int[] counter) {
        idx.put(k, counter[0]);
        low.put(k, counter[0]);
        counter[0]++;
        stack.push(k);
        onStack.add(k);
        frames.push(new Frame(k, depsOf(k)));
    }

    private Val evalCell(int k) {
        Cell c = cells.get(k);
        if (c.ast == null) {
            return PARSE;
        }
        Val v = eval(c.ast);
        if (v instanceof Num n && !Double.isFinite(n.v())) {
            return NUM;
        }
        return v;
    }

    private Val valueOf(int k) {
        Cell c = cells.get(k);
        if (c == null) {
            return EMPTY;
        }
        if (c.formula) {
            return done.get(k);
        }
        return c.direct;
    }

    private Val eval(Node n) {
        switch (n) {
            case Lit l -> {
                return Double.isFinite(l.v()) ? new Num(l.v()) : NUM;
            }
            case Ref r -> {
                Val v = valueOf(r.key());
                return switch (v) {
                    case Empty e -> new Num(0);
                    case Txt t -> VALUE;
                    case Err e -> e;
                    case Num x -> x;
                };
            }
            case Neg g -> {
                Val v = eval(g.a());
                if (v instanceof Num x) {
                    return new Num(-x.v());
                }
                return v;
            }
            case Bin b -> {
                Val l = eval(b.l());
                if (l instanceof Err) {
                    return l;
                }
                Val r = eval(b.r());
                if (r instanceof Err) {
                    return r;
                }
                double x = ((Num) l).v();
                double y = ((Num) r).v();
                double res;
                switch (b.op()) {
                    case '+' -> res = x + y;
                    case '-' -> res = x - y;
                    case '*' -> res = x * y;
                    default -> {
                        if (y == 0) {
                            return DIV0;
                        }
                        res = x / y;
                    }
                }
                return Double.isFinite(res) ? new Num(res) : NUM;
            }
            case Fn f -> {
                return evalFn(f);
            }
        }
    }

    private Val evalFn(Fn f) {
        double sum = 0;
        double min = 0;
        double max = 0;
        long count = 0;
        for (int r = f.r1(); r <= f.r2(); r++) {
            for (int c = f.c1(); c <= f.c2(); c++) {
                Val v = valueOf(key(r, c));
                if (v instanceof Err e) {
                    return e;
                }
                if (v instanceof Num x) {
                    double d = x.v();
                    if (count == 0) {
                        min = d;
                        max = d;
                    } else {
                        min = Math.min(min, d);
                        max = Math.max(max, d);
                    }
                    sum += d;
                    count++;
                }
            }
        }
        double res;
        switch (f.name()) {
            case "SUM" -> res = sum;
            case "MIN" -> res = min;
            case "MAX" -> res = max;
            case "COUNT" -> res = count;
            default -> {
                if (count == 0) {
                    return DIV0;
                }
                res = sum / count;
            }
        }
        return Double.isFinite(res) ? new Num(res) : NUM;
    }
}
