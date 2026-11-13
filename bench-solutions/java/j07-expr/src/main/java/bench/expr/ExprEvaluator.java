package bench.expr;

/** Recursive-descent arithmetic evaluator. */
public final class ExprEvaluator {
    private final String s;
    private int pos;

    private ExprEvaluator(String s) { this.s = s; }

    public static double eval(String expression) {
        if (expression == null) throw new IllegalArgumentException("null");
        ExprEvaluator p = new ExprEvaluator(expression);
        double v = p.expr();
        p.ws();
        if (p.pos != expression.length()) throw new IllegalArgumentException("trailing input");
        return v;
    }

    private void ws() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private double expr() {
        double v = term();
        while (true) {
            ws();
            if (pos < s.length() && s.charAt(pos) == '+') { pos++; v += term(); }
            else if (pos < s.length() && s.charAt(pos) == '-') { pos++; v -= term(); }
            else return v;
        }
    }

    private double term() {
        double v = factor();
        while (true) {
            ws();
            if (pos < s.length() && s.charAt(pos) == '*') { pos++; v *= factor(); }
            else if (pos < s.length() && s.charAt(pos) == '/') {
                pos++;
                double d = factor();
                if (d == 0) throw new ArithmeticException("division by zero");
                v /= d;
            } else return v;
        }
    }

    private double factor() {
        ws();
        if (pos >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(pos);
        if (c == '-') { pos++; return -factor(); }
        if (c == '(') {
            pos++;
            double v = expr();
            ws();
            if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalArgumentException("missing )");
            pos++;
            return v;
        }
        int start = pos;
        while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
        if (pos == start) throw new IllegalArgumentException("number expected");
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            int f = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            if (pos == f) throw new IllegalArgumentException("digits expected");
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
}
