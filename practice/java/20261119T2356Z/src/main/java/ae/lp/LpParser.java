package ae.lp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses the line-oriented LP text format described in TASK.md. */
public final class LpParser {
    private final Map<String, Integer> varIndex = new LinkedHashMap<>();
    private final List<Map<Integer, Rational>> rowTerms = new ArrayList<>();
    private final List<String> rowNames = new ArrayList<>();
    private final List<LpModel.Op> rowOps = new ArrayList<>();
    private final List<Rational> rowRhs = new ArrayList<>();
    private Map<Integer, Rational> objTerms;
    private LpModel.Sense sense;

    private LpParser() { }

    public static LpModel parse(String text) {
        return new LpParser().run(text);
    }

    private LpModel run(String text) {
        String[] lines = text.split("\n", -1);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) {
                continue;
            }
            Cursor cur = new Cursor(line, lineNo);
            if (sense == null) {
                parseObjective(cur);
            } else {
                parseConstraint(cur, names);
            }
        }
        if (sense == null) {
            throw new LpParseException(lines.length, "missing objective (expected 'max:' or 'min:')");
        }
        return build();
    }

    private static String stripComment(String s) {
        int h = s.indexOf('#');
        return h < 0 ? s : s.substring(0, h);
    }

    private void parseObjective(Cursor cur) {
        String word = cur.peekIdent();
        if (word == null || !(word.equalsIgnoreCase("max") || word.equalsIgnoreCase("min"))
                || !cur.identFollowedByColon()) {
            throw new LpParseException(cur.line, "first statement must be an objective ('max:' or 'min:')");
        }
        cur.readIdent();
        cur.expect(':');
        LpModel.Sense found = word.equalsIgnoreCase("max") ? LpModel.Sense.MAX : LpModel.Sense.MIN;
        objTerms = parseExpression(cur, false);
        cur.skipSpaces();
        if (!cur.atEnd()) {
            throw new LpParseException(cur.line, "trailing input after objective");
        }
        sense = found;
    }

    private void parseConstraint(Cursor cur, Set<String> names) {
        String word = cur.peekIdent();
        if (word != null && cur.identFollowedByColon()) {
            if (word.equalsIgnoreCase("max") || word.equalsIgnoreCase("min")) {
                throw new LpParseException(cur.line, "second objective");
            }
            cur.readIdent();
            cur.expect(':');
        } else {
            word = "c" + (rowNames.size() + 1);
        }
        String name = word;
        if (!names.add(name)) {
            throw new LpParseException(cur.line, "duplicate constraint name '" + name + "'");
        }
        Map<Integer, Rational> terms = parseExpression(cur, true);
        LpModel.Op op = parseOperator(cur);
        Rational rhs = parseConstant(cur);
        cur.skipSpaces();
        if (!cur.atEnd()) {
            throw new LpParseException(cur.line, "trailing input after right-hand side");
        }
        rowNames.add(name);
        rowTerms.add(terms);
        rowOps.add(op);
        rowRhs.add(rhs);
    }

    /** Parses {@code [+-] [rational] ident} terms until an operator or end of line. */
    private Map<Integer, Rational> parseExpression(Cursor cur, boolean constraint) {
        Map<Integer, Rational> terms = new LinkedHashMap<>();
        cur.skipSpaces();
        if (constraint && cur.atOperator()) {
            throw new LpParseException(cur.line, "constraint has no variables");
        }
        boolean first = true;
        while (true) {
            cur.skipSpaces();
            int sign = 1;
            if (cur.peek() == '+' || cur.peek() == '-') {
                sign = cur.peek() == '-' ? -1 : 1;
                cur.advance();
                cur.skipSpaces();
            } else if (!first) {
                break;
            }
            Rational coef = Rational.ONE;
            if (Character.isDigit(cur.peek())) {
                coef = cur.readRational();
                cur.skipSpaces();
            }
            String id = cur.peekIdent();
            if (id == null) {
                char bad = cur.peek();
                if (!cur.atEnd() && !cur.atOperator()) {
                    throw new LpParseException(cur.line, "unexpected character '" + bad + "'");
                }
                throw new LpParseException(cur.line, "expected term (a variable) here");
            }
            cur.readIdent();
            int idx = varIndex.computeIfAbsent(id.toLowerCase(java.util.Locale.ROOT), k -> varIndex.size());
            Rational signed = sign < 0 ? coef.negate() : coef;
            terms.merge(idx, signed, Rational::add);
            first = false;
        }
        return terms;
    }

    private LpModel.Op parseOperator(Cursor cur) {
        cur.skipSpaces();
        if (cur.startsWith("<=")) {
            cur.advance(2);
            return LpModel.Op.LE;
        }
        if (cur.startsWith(">=")) {
            cur.advance(2);
            return LpModel.Op.GE;
        }
        if (cur.startsWith("=")) {
            cur.advance(1);
            return LpModel.Op.EQ;
        }
        if (cur.atEnd() || Character.isLetterOrDigit(cur.peek()) || cur.peek() == '+' || cur.peek() == '-') {
            throw new LpParseException(cur.line, "expected comparison operator (<=, >= or =)");
        }
        throw new LpParseException(cur.line, "unexpected character '" + cur.peek() + "'");
    }

    private Rational parseConstant(Cursor cur) {
        cur.skipSpaces();
        int sign = 1;
        if (cur.peek() == '-' || cur.peek() == '+') {
            sign = cur.peek() == '-' ? -1 : 1;
            cur.advance();
            cur.skipSpaces();
        }
        if (!Character.isDigit(cur.peek())) {
            if (cur.atEnd()) {
                throw new LpParseException(cur.line, "expected constant after operator");
            }
            throw new LpParseException(cur.line, "right-hand side must be a constant");
        }
        Rational r = cur.readRational();
        return sign < 0 ? r.negate() : r;
    }

    private LpModel build() {
        int n = varIndex.size();
        Rational[] obj = dense(objTerms, n);
        List<LpModel.Constraint> cs = new ArrayList<>();
        for (int i = 0; i < rowNames.size(); i++) {
            cs.add(new LpModel.Constraint(rowNames.get(i), dense(rowTerms.get(i), n), rowOps.get(i), rowRhs.get(i)));
        }
        return new LpModel(sense, new ArrayList<>(varIndex.keySet()), obj, cs);
    }

    private static Rational[] dense(Map<Integer, Rational> terms, int n) {
        Rational[] out = new Rational[n];
        for (int j = 0; j < n; j++) {
            out[j] = terms.getOrDefault(j, Rational.ZERO);
        }
        return out;
    }

    /** Character cursor over one line. */
    private static final class Cursor {
        private final String s;
        private final int line;
        private int pos;

        Cursor(String s, int line) {
            this.s = s;
            this.line = line;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        char peek() {
            return atEnd() ? '\0' : s.charAt(pos);
        }

        void advance() {
            pos++;
        }

        void advance(int k) {
            pos += k;
        }

        boolean startsWith(String p) {
            return s.startsWith(p, pos);
        }

        boolean atOperator() {
            return startsWith("<=") || startsWith(">=") || peek() == '=';
        }

        void skipSpaces() {
            while (!atEnd() && Character.isWhitespace(peek())) {
                pos++;
            }
        }

        void expect(char c) {
            skipSpaces();
            if (peek() != c) {
                throw new LpParseException(line, "expected '" + c + "'");
            }
            pos++;
        }

        String peekIdent() {
            skipSpaces();
            if (!Character.isLetter(peek()) && peek() != '_') {
                return null;
            }
            int e = pos;
            while (e < s.length() && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '_')) {
                e++;
            }
            return s.substring(pos, e);
        }

        void readIdent() {
            pos += peekIdent().length();
        }

        boolean identFollowedByColon() {
            String id = peekIdent();
            int e = pos + id.length();
            while (e < s.length() && Character.isWhitespace(s.charAt(e))) {
                e++;
            }
            return e < s.length() && s.charAt(e) == ':';
        }

        Rational readRational() {
            int st = pos;
            while (Character.isDigit(peek())) {
                pos++;
            }
            if (peek() == '/') {
                pos++;
                if (!Character.isDigit(peek())) {
                    throw new LpParseException(line, "malformed rational (digits expected after '/')");
                }
                while (Character.isDigit(peek())) {
                    pos++;
                }
            }
            try {
                return Rational.parse(s.substring(st, pos));
            } catch (ArithmeticException e) {
                throw new LpParseException(line, "zero denominator");
            }
        }
    }
}
