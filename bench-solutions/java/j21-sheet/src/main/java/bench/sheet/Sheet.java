package bench.sheet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A small spreadsheet with formulas, ranges, error values and cycle detection. */
public final class Sheet {
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final String PARSE = "#PARSE!";
    private static final String VALUE = "#VALUE!";
    private static final String DIV0 = "#DIV/0!";
    private static final String NUM = "#NUM!";
    private static final String CYCLE = "#CYCLE!";

    // ---- values: Double (number), Text (text), Err (error), null (empty) ----
    private record Text(String s) {
    }

    private record Err(String code) {
    }

    // ---- AST ----
    private interface Node {
    }

    private record Num(double v) implements Node {
    }

    private record Ref(int col, int row) implements Node {
    }

    private record Neg(Node a) implements Node {
    }

    private record Bin(char op, Node l, Node r) implements Node {
    }

    private record Func(String name, int c1, int r1, int c2, int r2) implements Node {
    }

    private static final class Cell {
        final String input;
        Object constant; // Double, Text for non-formula
        Node ast; // formula, or null on parse error
        boolean formula;
        List<int[]> deps = new ArrayList<>();

        Cell(String input) {
            this.input = input;
        }
    }

    private final Map<Integer, Cell> cells = new HashMap<>();
    private final Map<Integer, Object> cache = new HashMap<>();
    private final Set<Integer> visiting = new HashSet<>();

    public Sheet() {
    }

    private static int key(int col, int row) {
        return col * 1000 + row;
    }

    private static int[] parseCell(String name) {
        if (name == null || name.length() < 2 || name.length() > 4) {
            return null;
        }
        char c = name.charAt(0);
        if (c < 'A' || c > 'Z') {
            return null;
        }
        if (name.charAt(1) < '1' || name.charAt(1) > '9') {
            return null;
        }
        int row = 0;
        for (int i = 1; i < name.length(); i++) {
            char d = name.charAt(i);
            if (d < '0' || d > '9') {
                return null;
            }
            row = row * 10 + (d - '0');
        }
        return new int[] {c - 'A', row};
    }

    private static int[] requireCell(String name) {
        int[] p = parseCell(name);
        if (p == null) {
            throw new IllegalArgumentException("bad cell name: " + name);
        }
        return p;
    }

    public void set(String cell, String input) {
        int[] p = requireCell(cell);
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        cache.clear();
        int k = key(p[0], p[1]);
        if (input.isEmpty()) {
            cells.remove(k);
            return;
        }
        Cell c = new Cell(input);
        if (input.charAt(0) == '=') {
            c.formula = true;
            try {
                Parser ps = new Parser(input.substring(1));
                c.ast = ps.parseAll();
                collectDeps(c.ast, c.deps);
            } catch (ParseFail e) {
                c.ast = null;
            }
        } else if (NUMBER.matcher(input).matches()) {
            c.constant = Double.parseDouble(input);
        } else {
            c.constant = new Text(input);
        }
        cells.put(k, c);
    }

    public String get(String cell) {
        int[] p = requireCell(cell);
        Object v = valueOf(p[0], p[1]);
        return display(v);
    }

    private static String display(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Text t) {
            return t.s();
        }
        if (v instanceof Err e) {
            return e.code();
        }
        double d = (Double) v;
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return NUM;
        }
        if (d == 0) {
            return "0";
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static void collectDeps(Node n, List<int[]> out) {
        if (n instanceof Ref r) {
            out.add(new int[] {r.col(), r.row(), r.col(), r.row()});
        } else if (n instanceof Neg g) {
            collectDeps(g.a(), out);
        } else if (n instanceof Bin b) {
            collectDeps(b.l(), out);
            collectDeps(b.r(), out);
        } else if (n instanceof Func f) {
            out.add(new int[] {f.c1(), f.r1(), f.c2(), f.r2()});
        }
    }

    // ---- evaluation ----

    private Object valueOf(int col, int row) {
        int k = key(col, row);
        Cell c = cells.get(k);
        if (c == null) {
            return null;
        }
        if (!c.formula) {
            return c.constant;
        }
        if (cache.containsKey(k)) {
            return cache.get(k);
        }
        if (visiting.contains(k)) {
            return new Err(CYCLE);
        }
        visiting.add(k);
        Object result;
        try {
            result = evalFormula(c);
        } finally {
            visiting.remove(k);
        }
        cache.put(k, result);
        return result;
    }

    private Object evalFormula(Cell c) {
        if (c.ast == null) {
            return new Err(PARSE);
        }
        boolean cyc = false;
        for (int[] d : c.deps) {
            for (int r = d[1]; r <= d[3]; r++) {
                for (int col = d[0]; col <= d[2]; col++) {
                    Object v = valueOf(col, r);
                    if (v instanceof Err e && e.code().equals(CYCLE)) {
                        cyc = true;
                    }
                }
            }
        }
        if (cyc) {
            return new Err(CYCLE);
        }
        return eval(c.ast);
    }

    private static Object finite(double d) {
        return (Double.isNaN(d) || Double.isInfinite(d)) ? new Err(NUM) : (Object) d;
    }

    private Object eval(Node n) {
        if (n instanceof Num x) {
            return x.v();
        }
        if (n instanceof Ref r) {
            return arith(valueOf(r.col(), r.row()));
        }
        if (n instanceof Neg g) {
            Object v = eval(g.a());
            return v instanceof Err ? v : finite(-(Double) v);
        }
        if (n instanceof Bin b) {
            Object l = eval(b.l());
            Object r = eval(b.r());
            if (l instanceof Err) {
                return l;
            }
            if (r instanceof Err) {
                return r;
            }
            double x = (Double) l;
            double y = (Double) r;
            return switch (b.op()) {
                case '+' -> finite(x + y);
                case '-' -> finite(x - y);
                case '*' -> finite(x * y);
                default -> y == 0 ? new Err(DIV0) : finite(x / y);
            };
        }
        return evalFunc((Func) n);
    }

    private static Object arith(Object v) {
        if (v == null) {
            return 0.0;
        }
        if (v instanceof Text) {
            return new Err(VALUE);
        }
        return v;
    }

    private Object evalFunc(Func f) {
        double sum = 0;
        double min = 0;
        double max = 0;
        int count = 0;
        for (int r = f.r1(); r <= f.r2(); r++) {
            for (int c = f.c1(); c <= f.c2(); c++) {
                Object v = valueOf(c, r);
                if (v instanceof Err) {
                    return v;
                }
                if (v instanceof Double d) {
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
        return switch (f.name()) {
            case "SUM" -> finite(sum);
            case "COUNT" -> (Object) (double) count;
            case "MIN" -> finite(min);
            case "MAX" -> finite(max);
            default -> count == 0 ? new Err(DIV0) : finite(sum / count);
        };
    }

    // ---- parsing ----

    private static final class ParseFail extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseFail() {
            super(null, null, false, false);
        }
    }

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
                throw new ParseFail();
            }
            return n;
        }

        private void skip() {
            while (pos < s.length() && s.charAt(pos) == ' ') {
                pos++;
            }
        }

        private Node expr() {
            Node left = term();
            while (true) {
                skip();
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    char op = s.charAt(pos++);
                    left = new Bin(op, left, term());
                } else {
                    return left;
                }
            }
        }

        private Node term() {
            Node left = unary();
            while (true) {
                skip();
                if (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                    char op = s.charAt(pos++);
                    left = new Bin(op, left, unary());
                } else {
                    return left;
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
                throw new ParseFail();
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Node n = expr();
                skip();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new ParseFail();
                }
                pos++;
                return n;
            }
            if (c >= '0' && c <= '9') {
                int st = pos;
                while (pos < s.length() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 128) {
                    pos++;
                }
                if (pos < s.length() && s.charAt(pos) == '.') {
                    pos++;
                    int ds = pos;
                    while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                        pos++;
                    }
                    if (pos == ds) {
                        throw new ParseFail();
                    }
                }
                return new Num(Double.parseDouble(s.substring(st, pos)));
            }
            if (isLetter(c)) {
                int st = pos;
                while (pos < s.length() && isLetter(s.charAt(pos))) {
                    pos++;
                }
                String letters = s.substring(st, pos);
                if (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos = st;
                    int[] r = cellRef();
                    return new Ref(r[0], r[1]);
                }
                if (pos < s.length() && s.charAt(pos) == '(') {
                    return function(letters);
                }
                throw new ParseFail();
            }
            throw new ParseFail();
        }

        private static boolean isLetter(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }

        /** Reads a letter followed by digits; must be a valid cell name. */
        private int[] cellRef() {
            int st = pos;
            while (pos < s.length() && isLetter(s.charAt(pos))) {
                pos++;
            }
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            int[] p = parseCell(s.substring(st, pos));
            if (p == null) {
                throw new ParseFail();
            }
            return p;
        }

        private Node function(String name) {
            switch (name) {
                case "SUM", "MIN", "MAX", "AVERAGE", "COUNT" -> {
                }
                default -> throw new ParseFail();
            }
            pos++; // '('
            skip();
            int[] a = cellRef();
            skip();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw new ParseFail();
            }
            pos++;
            skip();
            int[] b = cellRef();
            skip();
            if (pos >= s.length() || s.charAt(pos) != ')') {
                throw new ParseFail();
            }
            pos++;
            return new Func(name, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]),
                    Math.max(a[1], b[1]));
        }
    }
}
