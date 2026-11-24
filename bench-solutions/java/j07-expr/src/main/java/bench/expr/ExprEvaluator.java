package bench.expr;

/** Recursive-descent evaluator for simple arithmetic expressions. */
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
        p.skip();
        if (p.pos != p.s.length()) {
            throw new IllegalArgumentException("unexpected input at " + p.pos);
        }
        return v;
    }

    private void skip() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private boolean peek(char c) {
        skip();
        return pos < s.length() && s.charAt(pos) == c;
    }

    private double expr() {
        double v = term();
        while (true) {
            if (peek('+')) {
                pos++;
                v += term();
            } else if (peek('-')) {
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
            if (peek('*')) {
                pos++;
                v *= factor();
            } else if (peek('/')) {
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
        if (peek('-')) {
            pos++;
            return -factor();
        }
        if (peek('(')) {
            pos++;
            double v = expr();
            if (!peek(')')) {
                throw new IllegalArgumentException("expected )");
            }
            pos++;
            return v;
        }
        return number();
    }

    private double number() {
        skip();
        int start = pos;
        while (pos < s.length() && isDigit(s.charAt(pos))) {
            pos++;
        }
        if (pos == start) {
            throw new IllegalArgumentException("number expected at " + pos);
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            int f = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == f) {
                throw new IllegalArgumentException("digits expected after '.'");
            }
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
