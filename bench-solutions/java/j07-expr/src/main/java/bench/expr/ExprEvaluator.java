package bench.expr;

/** Recursive-descent arithmetic evaluator. */
public final class ExprEvaluator {
    private final String s;
    private int pos;

    private ExprEvaluator(String s) {
        this.s = s;
    }

    public static double eval(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("null expression");
        }
        ExprEvaluator p = new ExprEvaluator(expression);
        double v = p.expr(0);
        p.ws();
        if (p.pos != p.s.length()) {
            throw new IllegalArgumentException("unexpected input at " + p.pos);
        }
        return v;
    }

    private void ws() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private double expr(int depth) {
        double v = term(depth);
        while (true) {
            ws();
            if (pos < s.length() && s.charAt(pos) == '+') {
                pos++;
                v += term(depth);
            } else if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
                v -= term(depth);
            } else {
                return v;
            }
        }
    }

    private double term(int depth) {
        double v = factor(depth);
        while (true) {
            ws();
            if (pos < s.length() && s.charAt(pos) == '*') {
                pos++;
                v *= factor(depth);
            } else if (pos < s.length() && s.charAt(pos) == '/') {
                pos++;
                double d = factor(depth);
                if (d == 0.0) {
                    throw new ArithmeticException("division by zero");
                }
                v /= d;
            } else {
                return v;
            }
        }
    }

    private double factor(int depth) {
        if (depth > 2000) {
            throw new IllegalArgumentException("too deeply nested");
        }
        ws();
        if (pos >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char c = s.charAt(pos);
        if (c == '-') {
            pos++;
            return -factor(depth + 1);
        }
        if (c == '(') {
            pos++;
            double v = expr(depth + 1);
            ws();
            if (pos >= s.length() || s.charAt(pos) != ')') {
                throw new IllegalArgumentException("missing )");
            }
            pos++;
            return v;
        }
        if (c >= '0' && c <= '9') {
            int start = pos;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                int fs = pos;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                }
                if (pos == fs) {
                    throw new IllegalArgumentException("bad number");
                }
            }
            return Double.parseDouble(s.substring(start, pos));
        }
        throw new IllegalArgumentException("unexpected '" + c + "'");
    }
}
