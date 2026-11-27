package bench.sheet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A small spreadsheet with formulas, ranges and cycle detection. */
public final class Sheet {
    private static final Pattern CELL = Pattern.compile("[A-Z][1-9][0-9]{0,2}");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final String PARSE = "#PARSE!", VALUE = "#VALUE!", DIV = "#DIV/0!", NUM = "#NUM!", CYCLE = "#CYCLE!";

    private enum Kind { EMPTY, NUM, TEXT, ERR }

    private record V(Kind kind, double num, String str) {
        static final V EMPTY = new V(Kind.EMPTY, 0, "");
        static V num(double d) { return Double.isFinite(d) ? new V(Kind.NUM, d, null) : err(NUM); }
        static V text(String s) { return new V(Kind.TEXT, 0, s); }
        static V err(String e) { return new V(Kind.ERR, 0, e); }
        boolean isErr() { return kind == Kind.ERR; }
    }

    // AST
    private sealed interface Expr permits Const, Ref, Neg, Bin, Fn {}
    private record Const(double v) implements Expr {}
    private record Ref(String cell) implements Expr {}
    private record Neg(Expr e) implements Expr {}
    private record Bin(char op, Expr l, Expr r) implements Expr {}
    private record Fn(String name, int c1, int r1, int c2, int r2) implements Expr {}

    private static final class Formula {
        Expr ast;                     // null when parse error
        final List<String> refs = new ArrayList<>();
        final List<Fn> ranges = new ArrayList<>();
    }

    private final Map<String, Object> cells = new HashMap<>(); // V for constants, Formula for formulas
    private Map<String, V> memo = new HashMap<>();
    private final Set<String> inProgress = new HashSet<>();

    public Sheet() {
    }

    private static void check(String cell) {
        if (cell == null || !CELL.matcher(cell).matches()) {
            throw new IllegalArgumentException("bad cell name: " + cell);
        }
    }

    public void set(String cell, String input) {
        check(cell);
        if (input == null) {
            throw new IllegalArgumentException("null input");
        }
        memo = new HashMap<>();
        if (input.isEmpty()) {
            cells.remove(cell);
        } else if (input.charAt(0) == '=') {
            cells.put(cell, parseFormula(input.substring(1)));
        } else if (NUMBER.matcher(input).matches()) {
            cells.put(cell, V.num(Double.parseDouble(input)));
        } else {
            cells.put(cell, V.text(input));
        }
    }

    public String get(String cell) {
        check(cell);
        inProgress.clear();
        V v = evalCell(cell);
        return switch (v.kind()) {
            case EMPTY -> "";
            case TEXT, ERR -> v.str();
            case NUM -> v.num() == 0 ? "0" : BigDecimal.valueOf(v.num()).stripTrailingZeros().toPlainString();
        };
    }

    // ---- evaluation ----
    private V evalCell(String key) {
        V cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        Object o = cells.get(key);
        V result;
        if (o == null) {
            result = V.EMPTY;
        } else if (o instanceof V v) {
            result = v;
        } else {
            Formula f = (Formula) o;
            if (f.ast == null) {
                result = V.err(PARSE);
            } else {
                if (!inProgress.add(key)) {
                    return V.err(CYCLE); // not memoized
                }
                boolean cyc = false;
                for (String r : f.refs) {
                    if (isCycle(evalCell(r))) {
                        cyc = true;
                    }
                }
                for (Fn fn : f.ranges) {
                    for (int row = fn.r1(); row <= fn.r2(); row++) {
                        for (int col = fn.c1(); col <= fn.c2(); col++) {
                            if (isCycle(evalCell(name(col, row)))) {
                                cyc = true;
                            }
                        }
                    }
                }
                inProgress.remove(key);
                result = cyc ? V.err(CYCLE) : eval(f.ast);
            }
        }
        memo.put(key, result);
        return result;
    }

    private static boolean isCycle(V v) {
        return v.isErr() && v.str().equals(CYCLE);
    }

    private static String name(int col, int row) {
        return "" + (char) ('A' + col) + row;
    }

    private V eval(Expr e) {
        switch (e) {
            case Const c:
                return V.num(c.v());
            case Ref r: {
                V v = evalCell(r.cell());
                return switch (v.kind()) {
                    case EMPTY -> V.num(0);
                    case TEXT -> V.err(VALUE);
                    default -> v;
                };
            }
            case Neg n: {
                V v = eval(n.e());
                return v.isErr() ? v : V.num(-v.num());
            }
            case Bin b: {
                V l = eval(b.l());
                V r = eval(b.r());
                if (l.isErr()) {
                    return l;
                }
                if (r.isErr()) {
                    return r;
                }
                return switch (b.op()) {
                    case '+' -> V.num(l.num() + r.num());
                    case '-' -> V.num(l.num() - r.num());
                    case '*' -> V.num(l.num() * r.num());
                    default -> r.num() == 0 ? V.err(DIV) : V.num(l.num() / r.num());
                };
            }
            case Fn f:
                return evalFn(f);
        }
    }

    private V evalFn(Fn f) {
        double sum = 0, min = 0, max = 0;
        long count = 0;
        for (int row = f.r1(); row <= f.r2(); row++) {
            for (int col = f.c1(); col <= f.c2(); col++) {
                V v = evalCell(name(col, row));
                if (v.isErr()) {
                    return v;
                }
                if (v.kind() == Kind.NUM) {
                    double d = v.num();
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
            case "SUM" -> V.num(sum);
            case "MIN" -> V.num(min);
            case "MAX" -> V.num(max);
            case "COUNT" -> V.num(count);
            default -> count == 0 ? V.err(DIV) : V.num(sum / count);
        };
    }

    // ---- parsing ----
    private static Formula parseFormula(String s) {
        Formula f = new Formula();
        try {
            P p = new P(s, f);
            Expr e = p.expr();
            p.skip();
            if (p.i != s.length()) {
                throw new IllegalArgumentException();
            }
            f.ast = e;
        } catch (RuntimeException ex) {
            f.ast = null;
            f.refs.clear();
            f.ranges.clear();
        }
        return f;
    }

    private static final class P {
        final String s;
        final Formula f;
        int i;

        P(String s, Formula f) {
            this.s = s;
            this.f = f;
        }

        void skip() {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
        }

        boolean peek(char c) {
            skip();
            return i < s.length() && s.charAt(i) == c;
        }

        Expr expr() {
            Expr l = term();
            while (true) {
                if (peek('+') || peek('-')) {
                    char op = s.charAt(i++);
                    l = new Bin(op, l, term());
                } else {
                    return l;
                }
            }
        }

        Expr term() {
            Expr l = unary();
            while (true) {
                if (peek('*') || peek('/')) {
                    char op = s.charAt(i++);
                    l = new Bin(op, l, unary());
                } else {
                    return l;
                }
            }
        }

        Expr unary() {
            if (peek('-')) {
                i++;
                return new Neg(unary());
            }
            return primary();
        }

        Expr primary() {
            skip();
            if (i >= s.length()) {
                throw new IllegalArgumentException();
            }
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                int st = i;
                while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) {
                    i++;
                }
                if (i < s.length() && s.charAt(i) == '.') {
                    i++;
                    int d = i;
                    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                        i++;
                    }
                    if (i == d) {
                        throw new IllegalArgumentException();
                    }
                }
                return new Const(Double.parseDouble(s.substring(st, i)));
            }
            if (c == '(') {
                i++;
                Expr e = expr();
                if (!peek(')')) {
                    throw new IllegalArgumentException();
                }
                i++;
                return e;
            }
            String id = ident();
            if (peek('(')) {
                if (!(id.equals("SUM") || id.equals("MIN") || id.equals("MAX") || id.equals("AVERAGE") || id.equals("COUNT"))) {
                    throw new IllegalArgumentException();
                }
                i++;
                String a = ident();
                if (!peek(':')) {
                    throw new IllegalArgumentException();
                }
                i++;
                String b = ident();
                if (!peek(')')) {
                    throw new IllegalArgumentException();
                }
                i++;
                if (!CELL.matcher(a).matches() || !CELL.matcher(b).matches()) {
                    throw new IllegalArgumentException();
                }
                int ca = a.charAt(0) - 'A', cb = b.charAt(0) - 'A';
                int ra = Integer.parseInt(a.substring(1)), rb = Integer.parseInt(b.substring(1));
                Fn fn = new Fn(id, Math.min(ca, cb), Math.min(ra, rb), Math.max(ca, cb), Math.max(ra, rb));
                f.ranges.add(fn);
                return fn;
            }
            if (!CELL.matcher(id).matches()) {
                throw new IllegalArgumentException();
            }
            f.refs.add(id);
            return new Ref(id);
        }

        String ident() {
            skip();
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    i++;
                } else {
                    break;
                }
            }
            if (i == st) {
                throw new IllegalArgumentException();
            }
            return s.substring(st, i);
        }
    }
}
