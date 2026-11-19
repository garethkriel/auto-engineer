package bench.expr;

/** Recursive-descent evaluator for + - * / arithmetic with parentheses and unary minus. */
public final class ExprEvaluator {
    private final String s;
    private int pos;
    private boolean divZero;

    private ExprEvaluator(String s) {
        this.s = s;
    }

    public static double eval(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("null expression");
        }
        ExprEvaluator p = new ExprEvaluator(expression);
        double v;
        try {
            v = p.expr();
        } catch (StackOverflowError e) {
            throw new IllegalArgumentException("expression too deeply nested");
        }
        p.skip();
        if (p.pos != expression.length()) {
            throw new IllegalArgumentException("unexpected character at " + p.pos);
        }
        if (p.divZero) {
            throw new ArithmeticException("division by zero");
        }
        return v;
    }

    private void skip() {
        while (pos < s.length() && isWs(s.charAt(pos))) {
            pos++;
        }
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B';
    }

    private double expr() {
        double v = term();
        while (true) {
            skip();
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
            skip();
            if (pos < s.length() && s.charAt(pos) == '*') {
                pos++;
                v *= factor();
            } else if (pos < s.length() && s.charAt(pos) == '/') {
                pos++;
                double d = factor();
                if (d == 0.0) {
                    divZero = true;
                    v = 0.0;
                } else {
                    v /= d;
                }
            } else {
                return v;
            }
        }
    }

    private double factor() {
        skip();
        int negs = 0;
        while (pos < s.length() && s.charAt(pos) == '-') {
            negs++;
            pos++;
            skip();
        }
        double v;
        if (pos >= s.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        char c = s.charAt(pos);
        if (c == '(') {
            pos++;
            v = expr();
            skip();
            if (pos >= s.length() || s.charAt(pos) != ')') {
                throw new IllegalArgumentException("expected ')' at " + pos);
            }
            pos++;
        } else if (c >= '0' && c <= '9') {
            v = number();
        } else {
            throw new IllegalArgumentException("unexpected character at " + pos);
        }
        return (negs & 1) == 1 ? -v : v;
    }

    private double number() {
        int start = pos;
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
                throw new IllegalArgumentException("digits expected after '.'");
            }
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
