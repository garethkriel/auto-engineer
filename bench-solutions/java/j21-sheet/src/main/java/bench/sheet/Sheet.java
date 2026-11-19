package bench.sheet;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Small spreadsheet evaluator with formulas, ranges and cycle detection. */
public final class Sheet {
    private static final Pattern CELL = Pattern.compile("[A-Z][1-9][0-9]{0,2}");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    private static final int NUM = 0;
    private static final int TEXT = 1;
    private static final int EMPTY = 2;
    private static final int ERR = 3;

    private record Val(int kind, double n, String s) {
        static final Val EMPTY_VAL = new Val(EMPTY, 0, "");

        static Val num(double d) {
            return Double.isFinite(d) ? new Val(NUM, d, null) : err("#NUM!");
        }

        static Val err(String e) {
            return new Val(ERR, 0, e);
        }

        boolean isErr() {
            return kind == ERR;
        }
    }

    // ---- AST ----
    private interface Expr {
        Val eval(Sheet sh);
    }

    private record Cell(Val raw, Expr expr, List<String> deps) {
    }

    private record Rect(int c1, int r1, int c2, int r2) {
    }

    private final Map<String, Cell> cells = new HashMap<>();
    private final Map<String, Val> cache = new HashMap<>();

    public Sheet() {
    }

    private static void checkName(String cell) {
        if (cell == null || !CELL.matcher(cell).matches()) {
            throw new IllegalArgumentException("bad cell name: " + cell);
        }
    }

    public void set(String cell, String input) {
        checkName(cell);
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        Cell c;
        if (input.isEmpty()) {
            cells.remove(cell);
            cache.clear();
            return;
        } else if (input.charAt(0) == '=') {
            Parser p = new Parser(input.substring(1));
            Expr e = null;
            try {
                e = p.parseAll();
            } catch (ParseFail f) {
                e = null;
            }
            if (e == null) {
                c = new Cell(Val.err("#PARSE!"), null, List.of());
            } else {
                c = new Cell(null, e, new ArrayList<>(p.deps));
            }
        } else if (NUMBER.matcher(input).matches()) {
            c = new Cell(Val.num(Double.parseDouble(input)), null, List.of());
        } else {
            c = new Cell(new Val(TEXT, 0, input), null, List.of());
        }
        cells.put(cell, c);
        cache.clear();
    }

    public String get(String cell) {
        checkName(cell);
        Cell c = cells.get(cell);
        if (c == null) {
            return "";
        }
        Val v = c.expr == null ? c.raw : valueOfFormula(cell);
        return display(v);
    }

    private static String display(Val v) {
        switch (v.kind) {
            case TEXT:
                return v.s;
            case ERR:
                return v.s;
            case EMPTY:
                return "";
            default:
                if (v.n == 0) {
                    return "0";
                }
                return BigDecimal.valueOf(v.n).stripTrailingZeros().toPlainString();
        }
    }

    // ---- evaluation with iterative Tarjan ----
    private Val rawValue(String name) {
        Cell c = cells.get(name);
        if (c == null) {
            return Val.EMPTY_VAL;
        }
        if (c.expr == null) {
            return c.raw;
        }
        return cache.get(name);
    }

    private boolean isFormula(String name) {
        Cell c = cells.get(name);
        return c != null && c.expr != null;
    }

    private Val valueOfFormula(String start) {
        Val cached = cache.get(start);
        if (cached != null) {
            return cached;
        }
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Deque<Object[]> calls = new ArrayDeque<>(); // {name, Integer nextDep}
        int[] counter = {0};
        visit(start, index, low, onStack, stack, calls, counter);
        while (!calls.isEmpty()) {
            Object[] fr = calls.peek();
            String v = (String) fr[0];
            int i = (Integer) fr[1];
            List<String> deps = cells.get(v).deps;
            if (i < deps.size()) {
                fr[1] = i + 1;
                String w = deps.get(i);
                if (!isFormula(w) || cache.containsKey(w)) {
                    continue;
                }
                if (!index.containsKey(w)) {
                    visit(w, index, low, onStack, stack, calls, counter);
                } else if (onStack.contains(w)) {
                    low.put(v, Math.min(low.get(v), index.get(w)));
                }
            } else {
                calls.pop();
                if (!calls.isEmpty()) {
                    String parent = (String) calls.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    List<String> scc = new ArrayList<>();
                    String w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (!w.equals(v));
                    finishScc(scc);
                }
            }
        }
        return cache.get(start);
    }

    private void visit(String v, Map<String, Integer> index, Map<String, Integer> low, Set<String> onStack,
            Deque<String> stack, Deque<Object[]> calls, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        calls.push(new Object[] {v, 0});
    }

    private void finishScc(List<String> scc) {
        boolean cyclic = scc.size() > 1;
        String only = scc.get(0);
        if (!cyclic && cells.get(only).deps.contains(only)) {
            cyclic = true;
        }
        if (cyclic) {
            for (String m : scc) {
                cache.put(m, Val.err("#CYCLE!"));
            }
            return;
        }
        Cell c = cells.get(only);
        for (String d : c.deps) {
            Val dv = cache.get(d);
            if (dv != null && dv.isErr() && dv.s.equals("#CYCLE!")) {
                cache.put(only, dv);
                return;
            }
        }
        cache.put(only, c.expr.eval(this));
    }

    // ---- expression nodes ----
    private Val refValue(String name) {
        Val v = rawValue(name);
        switch (v.kind) {
            case EMPTY:
                return Val.num(0);
            case TEXT:
                return Val.err("#VALUE!");
            default:
                return v;
        }
    }

    private Val function(String fn, Rect r) {
        double sum = 0;
        double min = 0;
        double max = 0;
        int count = 0;
        for (int row = r.r1; row <= r.r2; row++) {
            for (int col = r.c1; col <= r.c2; col++) {
                Val v = rawValue(name(col, row));
                if (v.kind == ERR) {
                    return v;
                }
                if (v.kind == NUM) {
                    if (count == 0) {
                        min = v.n;
                        max = v.n;
                    } else {
                        min = Math.min(min, v.n);
                        max = Math.max(max, v.n);
                    }
                    sum += v.n;
                    count++;
                }
            }
        }
        switch (fn) {
            case "SUM":
                return Val.num(sum);
            case "COUNT":
                return Val.num(count);
            case "MIN":
                return Val.num(min);
            case "MAX":
                return Val.num(max);
            default:
                if (count == 0) {
                    return Val.err("#DIV/0!");
                }
                return Val.num(sum / count);
        }
    }

    private static String name(int col, int row) {
        return "" + (char) ('A' + col) + row;
    }

    // ---- parser ----
    private static final class ParseFail extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseFail() {
            super(null, null, false, false);
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;
        final Set<String> deps = new HashSet<>();

        Parser(String s) {
            this.s = s;
        }

        Expr parseAll() {
            Expr e = expr();
            skip();
            if (pos != s.length()) {
                throw new ParseFail();
            }
            return e;
        }

        private void skip() {
            while (pos < s.length() && s.charAt(pos) == ' ') {
                pos++;
            }
        }

        private boolean peek(char c) {
            skip();
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw new ParseFail();
            }
            pos++;
        }

        private Expr expr() {
            Expr left = term();
            while (true) {
                if (peek('+')) {
                    pos++;
                    left = bin('+', left, term());
                } else if (peek('-')) {
                    pos++;
                    left = bin('-', left, term());
                } else {
                    return left;
                }
            }
        }

        private Expr term() {
            Expr left = unary();
            while (true) {
                if (peek('*')) {
                    pos++;
                    left = bin('*', left, unary());
                } else if (peek('/')) {
                    pos++;
                    left = bin('/', left, unary());
                } else {
                    return left;
                }
            }
        }

        private Expr unary() {
            if (peek('-')) {
                pos++;
                Expr inner = unary();
                return sh -> {
                    Val v = inner.eval(sh);
                    return v.isErr() ? v : Val.num(-v.n);
                };
            }
            return primary();
        }

        private Expr bin(char op, Expr l, Expr r) {
            return sh -> {
                Val a = l.eval(sh);
                if (a.isErr()) {
                    return a;
                }
                Val b = r.eval(sh);
                if (b.isErr()) {
                    return b;
                }
                switch (op) {
                    case '+':
                        return Val.num(a.n + b.n);
                    case '-':
                        return Val.num(a.n - b.n);
                    case '*':
                        return Val.num(a.n * b.n);
                    default:
                        if (b.n == 0) {
                            return Val.err("#DIV/0!");
                        }
                        return Val.num(a.n / b.n);
                }
            };
        }

        private Expr primary() {
            skip();
            if (pos >= s.length()) {
                throw new ParseFail();
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Expr e = expr();
                expect(')');
                return e;
            }
            if (c >= '0' && c <= '9') {
                int st = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos < s.length() && s.charAt(pos) == '.') {
                    pos++;
                    if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                        throw new ParseFail();
                    }
                    while (pos < s.length() && isDigit(s.charAt(pos))) {
                        pos++;
                    }
                }
                double d = Double.parseDouble(s.substring(st, pos));
                Val v = Val.num(d);
                return sh -> v;
            }
            if (isAlnum(c)) {
                String id = ident();
                if (peek('(')) {
                    return function(id);
                }
                if (!CELL.matcher(id).matches()) {
                    throw new ParseFail();
                }
                deps.add(id);
                return sh -> sh.refValue(id);
            }
            throw new ParseFail();
        }

        private Expr function(String fn) {
            if (!(fn.equals("SUM") || fn.equals("MIN") || fn.equals("MAX") || fn.equals("AVERAGE")
                    || fn.equals("COUNT"))) {
                throw new ParseFail();
            }
            expect('(');
            skip();
            String a = ident();
            expect(':');
            skip();
            String b = ident();
            expect(')');
            if (!CELL.matcher(a).matches() || !CELL.matcher(b).matches()) {
                throw new ParseFail();
            }
            int ca = a.charAt(0) - 'A';
            int cb = b.charAt(0) - 'A';
            int ra = Integer.parseInt(a.substring(1));
            int rb = Integer.parseInt(b.substring(1));
            Rect r = new Rect(Math.min(ca, cb), Math.min(ra, rb), Math.max(ca, cb), Math.max(ra, rb));
            for (int row = r.r1; row <= r.r2; row++) {
                for (int col = r.c1; col <= r.c2; col++) {
                    deps.add(name(col, row));
                }
            }
            return sh -> sh.function(fn, r);
        }

        private String ident() {
            skip();
            int st = pos;
            while (pos < s.length() && isAlnum(s.charAt(pos))) {
                pos++;
            }
            if (st == pos) {
                throw new ParseFail();
            }
            return s.substring(st, pos);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isAlnum(char c) {
            return isDigit(c) || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }
    }
}
