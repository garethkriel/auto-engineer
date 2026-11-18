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
            throw new IllegalArgumentException("expression is null");
        }
        ExprEvaluator p = new ExprEvaluator(expression);
        try {
            double v = p.expr();
            p.skipWs();
            if (p.pos != p.s.length()) {
                throw new IllegalArgumentException("unexpected input at " + p.pos);
            }
            return v;
        } catch (StackOverflowError e) {
            throw new IllegalArgumentException("expression too deeply nested", e);
        }
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private boolean peekIs(char c) {
        skipWs();
        return pos < s.length() && s.charAt(pos) == c;
    }

    private double expr() {
        double v = term();
        while (true) {
            if (peekIs('+')) {
                pos++;
                v += term();
            } else if (peekIs('-')) {
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
            if (peekIs('*')) {
                pos++;
                v *= factor();
            } else if (peekIs('/')) {
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
        boolean neg = false;
        while (peekIs('-')) {
            pos++;
            neg = !neg;
        }
        double v;
        if (peekIs('(')) {
            pos++;
            v = expr();
            if (!peekIs(')')) {
                throw new IllegalArgumentException("expected ')' at " + pos);
            }
            pos++;
        } else {
            v = number();
        }
        return neg ? -v : v;
    }

    private double number() {
        skipWs();
        int start = pos;
        while (pos < s.length() && isDigit(s.charAt(pos))) {
            pos++;
        }
        if (pos == start) {
            throw new IllegalArgumentException("expected number at " + pos);
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            int fs = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == fs) {
                throw new IllegalArgumentException("digits expected after '.'");
            }
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
