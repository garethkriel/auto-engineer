package bench.expr;

public final class ExprEvaluator {
    private final String s;
    private int pos;

    private ExprEvaluator(String s) { this.s = s; }

    public static double eval(String expression) {
        if (expression == null) throw new IllegalArgumentException("null");
        ExprEvaluator p = new ExprEvaluator(expression);
        double v = p.expr(0);
        p.skip();
        if (p.pos != p.s.length()) throw new IllegalArgumentException("unexpected input at " + p.pos);
        return v;
    }

    private void skip() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private double expr(int depth) {
        double v = term(depth);
        while (true) {
            skip();
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                char op = s.charAt(pos++);
                double r = term(depth);
                v = op == '+' ? v + r : v - r;
            } else return v;
        }
    }

    private double term(int depth) {
        double v = factor(depth);
        while (true) {
            skip();
            if (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                char op = s.charAt(pos++);
                double r = factor(depth);
                if (op == '*') v *= r;
                else {
                    if (r == 0) throw new ArithmeticException("division by zero");
                    v /= r;
                }
            } else return v;
        }
    }

    private double factor(int depth) {
        if (depth > 2000) throw new IllegalArgumentException("too deeply nested");
        skip();
        if (pos >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(pos);
        if (c == '-') { pos++; return -factor(depth + 1); }
        if (c == '(') {
            pos++;
            double v = expr(depth + 1);
            skip();
            if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalArgumentException("missing )");
            pos++;
            return v;
        }
        if (c >= '0' && c <= '9') {
            int start = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                int f = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
                if (pos == f) throw new IllegalArgumentException("digits expected after '.'");
            }
            return Double.parseDouble(s.substring(start, pos));
        }
        throw new IllegalArgumentException("unexpected '" + c + "'");
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
}
