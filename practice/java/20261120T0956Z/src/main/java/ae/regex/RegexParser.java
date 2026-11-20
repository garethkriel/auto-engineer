package ae.regex;

/** Recursive-descent parser: alt := cat ('|' cat)*, cat := repeat*, repeat := atom quantifier?. */
public final class RegexParser {
    /** Largest repetition bound accepted in {@code {m,n}}. */
    public static final int MAX_BOUND = 1000;

    static final CharSet DIGIT = CharSet.range('0', '9');
    static final CharSet WORD = CharSet.range('a', 'z').union(CharSet.range('A', 'Z')).union(DIGIT).union(CharSet.of('_'));
    static final CharSet SPACE = CharSet.of(' ').union(CharSet.range('\t', '\r'));
    static final CharSet DOT = CharSet.ANY.minus(
            CharSet.of('\n').union(CharSet.of('\r')).union(CharSet.of(0x85)).union(CharSet.range(0x2028, 0x2029)));

    private final String s;
    private int pos;

    private RegexParser(String s) {
        this.s = s;
    }

    public static Node parse(String pattern) {
        RegexParser p = new RegexParser(pattern);
        Node n = p.alt();
        if (p.pos < pattern.length()) {
            throw new RegexSyntaxException("unmatched ')'", p.pos);
        }
        return n;
    }

    private Node alt() {
        Node left = cat();
        while (pos < s.length() && s.charAt(pos) == '|') {
            pos++;
            left = new Node.Alt(left, cat());
        }
        return left;
    }

    private Node cat() {
        Node acc = null;
        while (pos < s.length() && s.charAt(pos) != '|' && s.charAt(pos) != ')') {
            Node piece = repeat();
            acc = acc == null ? piece : new Node.Cat(acc, piece);
        }
        return acc == null ? new Node.Empty() : acc;
    }

    private Node repeat() {
        Node atom = atom();
        if (pos >= s.length()) {
            return atom;
        }
        char c = s.charAt(pos);
        Node result;
        switch (c) {
            case '*' -> {
                pos++;
                result = new Node.Star(atom);
            }
            case '+' -> {
                pos++;
                result = new Node.Repeat(atom, 1, -1);
            }
            case '?' -> {
                pos++;
                result = new Node.Repeat(atom, 0, 1);
            }
            case '{' -> result = braces(atom);
            default -> {
                return atom;
            }
        }
        if (pos < s.length() && "*+?{".indexOf(s.charAt(pos)) >= 0) {
            throw new RegexSyntaxException("dangling quantifier", pos);
        }
        return result;
    }

    private Node braces(Node atom) {
        int open = pos;
        pos++;
        int min = number();
        if (min < 0) {
            throw new RegexSyntaxException("malformed quantifier", open);
        }
        int max = min;
        if (pos < s.length() && s.charAt(pos) == ',') {
            pos++;
            max = number();
            // "{m,}" has no digits after the comma: unbounded
            if (max < 0) {
                max = -1;
            }
        }
        if (pos >= s.length() || s.charAt(pos) != '}') {
            throw new RegexSyntaxException("malformed quantifier", open);
        }
        pos++;
        if (min > MAX_BOUND || max > MAX_BOUND || (max != -1 && max < min)) {
            throw new RegexSyntaxException("quantifier bounds out of range", open);
        }
        return new Node.Repeat(atom, min, max);
    }

    /** Reads decimal digits; returns -1 if there are none; saturates above the bound so overflow cannot occur. */
    private int number() {
        int start = pos;
        long v = 0;
        while (pos < s.length() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 128) {
            v = Math.min(v * 10 + (s.charAt(pos) - '0'), MAX_BOUND + 1L);
            pos++;
        }
        return pos == start ? -1 : (int) v;
    }

    private Node atom() {
        char c = s.charAt(pos);
        switch (c) {
            case '(' -> {
                int open = pos;
                pos++;
                Node inner = alt();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new RegexSyntaxException("unclosed group", open);
                }
                pos++;
                return inner;
            }
            case '*', '+', '?', '{' -> throw new RegexSyntaxException("nothing to repeat", pos);
            case '^', '$' -> throw new RegexSyntaxException("anchors are not supported", pos);
            case '[' -> {
                return new Node.Lit(charClass());
            }
            case '.' -> {
                pos++;
                return new Node.Lit(DOT);
            }
            case '\\' -> {
                return new Node.Lit(escape().set());
            }
            default -> {
                pos++;
                return new Node.Lit(CharSet.of(c));
            }
        }
    }

    /** Either a single char ({@code ch >= 0}) or a predefined set ({@code ch == -1}). */
    private record Item(CharSet set, int ch, int index) { }

    private Item escape() {
        int start = pos;
        pos++;
        if (pos >= s.length()) {
            throw new RegexSyntaxException("trailing backslash", start);
        }
        char e = s.charAt(pos++);
        switch (e) {
            case 'd': return new Item(DIGIT, -1, start);
            case 'D': return new Item(DIGIT.negate(), -1, start);
            case 'w': return new Item(WORD, -1, start);
            case 'W': return new Item(WORD.negate(), -1, start);
            case 's': return new Item(SPACE, -1, start);
            case 'S': return new Item(SPACE.negate(), -1, start);
            case 'n': return single('\n', start);
            case 't': return single('\t', start);
            case 'r': return single('\r', start);
            case 'f': return single('\f', start);
            default:
                if (e < 128 && Character.isLetterOrDigit(e)) {
                    throw new RegexSyntaxException("unknown escape '\\" + e + "'", start);
                }
                return single(e, start);
        }
    }

    private static Item single(int c, int index) {
        return new Item(CharSet.of(c), c, index);
    }

    private Item classItem() {
        char c = s.charAt(pos);
        if (c == '\\') {
            return escape();
        }
        if (c == '[') {
            throw new RegexSyntaxException("nested character class is not supported", pos);
        }
        int start = pos++;
        return single(c, start);
    }

    private CharSet charClass() {
        int open = pos;
        pos++;
        boolean negate = pos < s.length() && s.charAt(pos) == '^';
        if (negate) {
            pos++;
        }
        CharSet acc = CharSet.EMPTY;
        boolean first = true;
        while (true) {
            if (pos >= s.length()) {
                throw new RegexSyntaxException("unclosed class", open);
            }
            if (s.charAt(pos) == ']') {
                if (first) {
                    throw new RegexSyntaxException("empty class", pos);
                }
                pos++;
                break;
            }
            first = false;
            Item lo = classItem();
            if (lo.ch() >= 0 && pos + 1 < s.length() && s.charAt(pos) == '-' && s.charAt(pos + 1) != ']') {
                pos++;
                Item hi = classItem();
                if (hi.ch() < 0) {
                    throw new RegexSyntaxException("range end must be a single character", hi.index());
                }
                if (hi.ch() < lo.ch()) {
                    throw new RegexSyntaxException("range out of order", lo.index());
                }
                acc = acc.union(CharSet.range(lo.ch(), hi.ch()));
            } else {
                acc = acc.union(lo.set());
            }
        }
        return negate ? acc.negate() : acc;
    }
}
