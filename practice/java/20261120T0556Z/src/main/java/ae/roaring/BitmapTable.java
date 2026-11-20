package ae.roaring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A read-only table answering boolean queries with bitmap algebra. Category columns keep one {@link RoaringBitmap} per
 * distinct value; number columns keep a {@link BitSlicedIndex}. Query grammar: see {@code TASK.md}.
 */
public final class BitmapTable {
    private final Map<String, Map<String, RoaringBitmap>> categories;
    private final Map<String, BitSlicedIndex> numbers;
    private final int rows;
    private final RoaringBitmap universe;

    private BitmapTable(Map<String, Map<String, RoaringBitmap>> categories, Map<String, BitSlicedIndex> numbers, int rows) {
        this.categories = categories;
        this.numbers = numbers;
        this.rows = rows;
        this.universe = new RoaringBitmap();
        universe.addRange(0, rows);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int rowCount() {
        return rows;
    }

    /** Row ids matching {@code query}. */
    public RoaringBitmap eval(String query) {
        Parser p = new Parser(query);
        RoaringBitmap r = p.parseOr();
        p.expectEnd();
        return r;
    }

    public long count(String query) {
        return eval(query).cardinality();
    }

    /** Sum of an int column over the rows matching {@code query}. */
    public long sum(String column, String query) {
        BitSlicedIndex ix = numbers.get(column);
        if (ix == null) throw new IllegalArgumentException("not a number column: " + column);
        return ix.sum(eval(query));
    }

    // ---------------- builder ----------------

    public static final class Builder {
        private final Map<String, List<String>> catValues = new LinkedHashMap<>();
        private final Map<String, List<Integer>> numValues = new LinkedHashMap<>();
        private int rows;

        public Builder category(String name) {
            declare(name);
            catValues.put(name, new ArrayList<>());
            return this;
        }

        public Builder number(String name) {
            declare(name);
            numValues.put(name, new ArrayList<>());
            return this;
        }

        private void declare(String name) {
            if (catValues.containsKey(name) || numValues.containsKey(name)) {
                throw new IllegalArgumentException("duplicate column " + name);
            }
        }

        /** Appends a row; every declared column needs a value and no other keys are allowed. */
        public Builder row(Map<String, String> row) {
            Set<String> declared = new java.util.HashSet<>(catValues.keySet());
            declared.addAll(numValues.keySet());
            for (String k : row.keySet()) {
                if (!declared.contains(k)) throw new IllegalArgumentException("row " + rows + ": unknown column " + k);
            }
            Map<String, Integer> parsed = new HashMap<>();
            for (String col : declared) {
                String v = row.get(col);
                if (v == null) throw new IllegalArgumentException("row " + rows + ": missing column " + col);
                if (numValues.containsKey(col)) {
                    try {
                        int x = Integer.parseInt(v);
                        if (x < 0) throw new IllegalArgumentException("row " + rows + ": negative value in " + col);
                        parsed.put(col, x);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("row " + rows + ": column " + col + " is not an int: " + v);
                    }
                }
            }
            // Everything validated: only now mutate.
            for (var e : catValues.entrySet()) e.getValue().add(row.get(e.getKey()));
            for (var e : numValues.entrySet()) e.getValue().add(parsed.get(e.getKey()));
            rows++;
            return this;
        }

        public BitmapTable build() {
            Map<String, Map<String, RoaringBitmap>> cats = new HashMap<>();
            for (var e : catValues.entrySet()) {
                Map<String, RoaringBitmap> byValue = new HashMap<>();
                List<String> vals = e.getValue();
                for (int r = 0; r < vals.size(); r++) byValue.computeIfAbsent(vals.get(r), k -> new RoaringBitmap()).add(r);
                byValue.values().forEach(RoaringBitmap::runOptimize);
                cats.put(e.getKey(), byValue);
            }
            Map<String, BitSlicedIndex> nums = new HashMap<>();
            for (var e : numValues.entrySet()) {
                nums.put(e.getKey(), BitSlicedIndex.of(e.getValue().stream().mapToInt(Integer::intValue).toArray()));
            }
            return new BitmapTable(cats, nums, rows);
        }
    }

    // ---------------- tokenizer + parser ----------------

    private enum Kind { WORD, STRING, SYMBOL, END }

    private record Token(Kind kind, String text, int column) {
        boolean isKeyword(String kw) {
            return kind == Kind.WORD && text.equalsIgnoreCase(kw);
        }

        boolean isSymbol(String s) {
            return kind == Kind.SYMBOL && text.equals(s);
        }

        boolean anyKeyword() {
            return kind == Kind.WORD && Set.of("AND", "OR", "NOT", "IN", "BETWEEN").contains(text.toUpperCase(Locale.ROOT));
        }

        String describe() {
            return kind == Kind.END ? "end of query" : "'" + text + "'";
        }
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int col = i + 1;
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')' || c == ',' || c == '=') {
                out.add(new Token(Kind.SYMBOL, String.valueOf(c), col));
                i++;
            } else if (c == '!') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '=') {
                    out.add(new Token(Kind.SYMBOL, "!=", col));
                    i += 2;
                } else {
                    throw new QueryException("unexpected character '!'", col);
                }
            } else if (c == '<' || c == '>') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '=') {
                    out.add(new Token(Kind.SYMBOL, c + "=", col));
                    i += 2;
                } else {
                    out.add(new Token(Kind.SYMBOL, String.valueOf(c), col));
                    i++;
                }
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                if (!closed) throw new QueryException("unterminated quoted value", col);
                out.add(new Token(Kind.STRING, sb.toString(), col));
            } else if (isWordChar(c)) {
                int j = i;
                while (j < s.length() && isWordChar(s.charAt(j))) j++;
                out.add(new Token(Kind.WORD, s.substring(i, j), col));
                i = j;
            } else {
                throw new QueryException("unexpected character '" + c + "'", col);
            }
        }
        out.add(new Token(Kind.END, "", s.length() + 1));
        return out;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private final class Parser {
        private final List<Token> toks;
        private int pos;

        Parser(String query) {
            this.toks = tokenize(query);
        }

        private Token peek() { return toks.get(pos); }

        private Token next() { return toks.get(pos++); }

        void expectEnd() {
            if (peek().kind() != Kind.END) throw new QueryException("expected end of query but found " + peek().describe(), peek().column());
        }

        RoaringBitmap parseOr() {
            RoaringBitmap left = parseAnd();
            while (peek().isKeyword("OR")) {
                next();
                left = RoaringBitmap.or(left, parseAnd());
            }
            return left;
        }

        RoaringBitmap parseAnd() {
            RoaringBitmap left = parseNot();
            while (peek().isKeyword("AND")) {
                next();
                left = RoaringBitmap.and(left, parseNot());
            }
            return left;
        }

        RoaringBitmap parseNot() {
            if (peek().isKeyword("NOT")) {
                next();
                return RoaringBitmap.andNot(universe, parseNot());
            }
            return parseAtom();
        }

        RoaringBitmap parseAtom() {
            Token t = peek();
            if (t.isSymbol("(")) {
                next();
                RoaringBitmap inner = parseOr();
                if (!peek().isSymbol(")")) throw new QueryException("expected ')' but found " + peek().describe(), peek().column());
                next();
                return inner;
            }
            if (t.kind() != Kind.WORD || t.anyKeyword()) {
                throw new QueryException("expected condition but found " + t.describe(), t.column());
            }
            next();
            String col = t.text();
            boolean isCategory = categories.containsKey(col);
            if (!isCategory && !numbers.containsKey(col)) throw new QueryException("unknown column '" + col + "'", t.column());
            Token op = next();
            if (op.isKeyword("IN")) return parseIn(t, isCategory);
            if (op.isKeyword("BETWEEN")) return parseBetween(t, isCategory);
            if (op.isSymbol("=")) return equal(t, isCategory, value());
            if (op.isSymbol("!=")) return RoaringBitmap.andNot(universe, equal(t, isCategory, value()));
            if (op.isSymbol("<") || op.isSymbol("<=") || op.isSymbol(">") || op.isSymbol(">=")) {
                if (isCategory) throw new QueryException("operator " + op.text() + " needs a numeric column, but '" + col + "' is a category", t.column());
                int v = integer();
                BitSlicedIndex ix = numbers.get(col);
                return switch (op.text()) {
                    case "<" -> ix.lessThan(v);
                    case "<=" -> ix.lessOrEqual(v);
                    case ">" -> ix.greaterThan(v);
                    default -> ix.greaterOrEqual(v);
                };
            }
            throw new QueryException("expected operator but found " + op.describe(), op.column());
        }

        private RoaringBitmap parseIn(Token colTok, boolean isCategory) {
            Token open = next();
            if (!open.isSymbol("(")) throw new QueryException("expected '(' but found " + open.describe(), open.column());
            RoaringBitmap acc = new RoaringBitmap();
            while (true) {
                acc = RoaringBitmap.or(acc, equal(colTok, isCategory, value()));
                Token sep = next();
                if (sep.isSymbol(")")) return acc;
                if (!sep.isSymbol(",")) throw new QueryException("expected ',' or ')' but found " + sep.describe(), sep.column());
            }
        }

        private RoaringBitmap parseBetween(Token colTok, boolean isCategory) {
            if (isCategory) throw new QueryException("BETWEEN needs a numeric column, but '" + colTok.text() + "' is a category", colTok.column());
            int lo = integer();
            Token and = next();
            if (!and.isKeyword("AND")) throw new QueryException("expected AND but found " + and.describe(), and.column());
            int hi = integer();
            return numbers.get(colTok.text()).between(lo, hi);
        }

        /** A bare word or quoted string; bare keywords must be quoted. */
        private Token value() {
            Token v = next();
            if (v.kind() == Kind.STRING) return v;
            if (v.kind() == Kind.WORD) {
                if (v.anyKeyword()) throw new QueryException("keyword " + v.text().toUpperCase(Locale.ROOT) + " cannot be a value; quote it", v.column());
                return v;
            }
            throw new QueryException("expected value but found " + v.describe(), v.column());
        }

        private int integer() {
            Token v = next();
            if (v.kind() == Kind.WORD && !v.anyKeyword()) {
                try {
                    return Integer.parseInt(v.text());
                } catch (NumberFormatException e) {
                    // fall through to the error below
                }
            }
            throw new QueryException("expected integer but found " + v.describe(), v.column());
        }

        private RoaringBitmap equal(Token colTok, boolean isCategory, Token value) {
            if (isCategory) {
                RoaringBitmap b = categories.get(colTok.text()).get(value.text());
                return b == null ? new RoaringBitmap() : b.copy();
            }
            if (value.kind() != Kind.WORD) throw new QueryException("expected integer but found " + value.describe(), value.column());
            try {
                return numbers.get(colTok.text()).equalTo(Integer.parseInt(value.text()));
            } catch (NumberFormatException e) {
                throw new QueryException("expected integer but found " + value.describe(), value.column());
            }
        }
    }
}
