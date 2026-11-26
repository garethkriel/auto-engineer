package bench.expr;

/** Recursive-descent arithmetic evaluator. */
public final class ExprEvaluator {
    private final String s;
    private int pos;

    private ExprEvaluator(String s) { this.s = s; }

    public static double eval(String expression) {
        if (expression == null) throw new IllegalArgumentException("null expression");
        ExprEvaluator p = new ExprEvaluator(expression);
        double v = p.expr();
        p.skip();
        if (p.pos != p.s.length()) throw new IllegalArgumentException("unexpected input at " + p.pos);
        return v;
    }

    private void skip() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private boolean accept(char c) {
        skip();
        if (pos < s.length() && s.charAt(pos) == c) { pos++; return true; }
        return false;
    }

    private double expr() {
        double v = term();
        while (true) {
            if (accept('+')) v += term();
            else if (accept('-')) v -= term();
            else return v;
        }
    }

    private double term() {
        double v = factor();
        while (true) {
            if (accept('*')) v *= factor();
            else if (accept('/')) {
                double d = factor();
                if (d == 0) throw new ArithmeticException("division by zero");
                v /= d;
            } else return v;
        }
    }

    private double factor() {
        if (accept('-')) return -factor();
        if (accept('(')) {
            double v = expr();
            if (!accept(')')) throw new IllegalArgumentException("expected ')'");
            return v;
        }
        return number();
    }

    private double number() {
        skip();
        int start = pos;
        while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
        if (pos == start) throw new IllegalArgumentException("number expected at " + pos);
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            int f = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            if (pos == f) throw new IllegalArgumentException("digits expected after '.'");
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
}
