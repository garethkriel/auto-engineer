package bench.expr;

/** Recursive-descent evaluator for + - * / arithmetic. */
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
        double v = p.expr();
        p.skipWs();
        if (p.pos != p.s.length()) {
            throw new IllegalArgumentException("unexpected input at " + p.pos);
        }
        return v;
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private double expr() {
        double v = term();
        while (true) {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '+') {
                pos++;
                v += term();
            } else if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
                v -= term();
            } else {
                return v;
            }
        }
    }

    private double term() {
        double v = factor();
        while (true) {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '*') {
                pos++;
                v *= factor();
            } else if (pos < s.length() && s.charAt(pos) == '/') {
                pos++;
                double d = factor();
                if (d == 0.0) {
                    throw new ArithmeticException("division by zero");
                }
                v /= d;
            } else {
                return v;
            }
        }
    }

    private double factor() {
        skipWs();
        if (pos >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char c = s.charAt(pos);
        if (c == '-') {
            pos++;
            return -factor();
        }
        if (c == '(') {
            pos++;
            double v = expr();
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != ')') {
                throw new IllegalArgumentException("expected )");
            }
            pos++;
            return v;
        }
        return number();
    }

    private double number() {
        int start = pos;
        while (pos < s.length() && isDigit(s.charAt(pos))) {
            pos++;
        }
        if (pos == start) {
            throw new IllegalArgumentException("number expected at " + pos);
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            int frac = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == frac) {
                throw new IllegalArgumentException("digits expected after '.'");
            }
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
