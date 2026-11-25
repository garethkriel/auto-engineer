package bench.expr;

/** Recursive-descent evaluator for the arithmetic grammar in the task. */
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

    private int peek() {
        skip();
        return pos < s.length() ? s.charAt(pos) : -1;
    }

    private double expr() {
        double v = term();
        for (;;) {
            int c = peek();
            if (c == '+') {
                pos++;
                v += term();
            } else if (c == '-') {
                pos++;
                v -= term();
            } else {
                return v;
            }
        }
    }

    private double term() {
        double v = factor();
        for (;;) {
            int c = peek();
            if (c == '*') {
                pos++;
                v *= factor();
            } else if (c == '/') {
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
        int c = peek();
        if (c == '-') {
            pos++;
            return -factor();
        }
        if (c == '(') {
            pos++;
            double v = expr();
            if (peek() != ')') {
                throw new IllegalArgumentException("expected ')'");
            }
            pos++;
            return v;
        }
        if (c >= '0' && c <= '9') {
            return number();
        }
        throw new IllegalArgumentException("unexpected token at " + pos);
    }

    private double number() {
        int start = pos;
        while (pos < s.length() && isDigit(s.charAt(pos))) {
            pos++;
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                throw new IllegalArgumentException("digit expected after '.'");
            }
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
