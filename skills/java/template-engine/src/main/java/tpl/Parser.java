package tpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tpl.Node.Cond;
import tpl.Node.Filter;
import tpl.Node.Operand;

/** Two-stage parser: split the text into raw pieces, then build the tree with recursive descent. */
final class Parser {
    static final Set<String> FILTERS = Set.of("upper", "lower", "trim", "length", "raw", "default");
    private static final Set<String> RESERVED = Set.of("not", "and", "or", "in");

    private enum RawKind { TEXT, OUTPUT, TAG }

    /** A piece of the template: text, or the inside of one {@code {{ }}} / {@code {% %}} tag. */
    private record Raw(RawKind kind, String text, int open, int contentOffset) { }

    private enum Type { NAME, STRING, INT, PIPE, EQ, NE }

    private record Tk(Type type, String text, int offset) {
        boolean isWord(String word) {
            return type == Type.NAME && text.equals(word);
        }

        String describe() {
            return type == Type.STRING ? "string" : "'" + text + "'";
        }
    }

    /** A tag that ended a block: its tokens and the offset of the opening delimiter. */
    private record Stop(List<Tk> tokens, int open, int end) { }

    private final String src;
    private final Source source;
    private final List<Raw> raws = new ArrayList<>();
    private int index;
    private Stop stop;

    private Parser(String src) {
        this.src = src;
        this.source = new Source(src);
    }

    static Template parse(String text) {
        Parser p = new Parser(text);
        p.split();
        List<Node> nodes = p.nodes(Set.of());
        return new Template(nodes, p.source);
    }

    // ---- stage 1: raw pieces ----

    private void split() {
        int pos = 0;
        boolean stripNext = false;
        while (pos < src.length()) {
            int open = nextOpen(pos);
            int textEnd = open < 0 ? src.length() : open;
            String text = src.substring(pos, textEnd);
            if (stripNext) {
                text = text.stripLeading();
            }
            stripNext = false;
            if (open < 0) {
                addText(text, pos);
                return;
            }
            char kind = src.charAt(open + 1);
            String closer = kind == '{' ? "}}" : kind == '%' ? "%}" : "#}";
            int close = src.indexOf(closer, open + 2);
            if (close < 0) {
                throw source.syntax("unclosed '" + src.substring(open, open + 2) + "' tag", open);
            }
            String content = src.substring(open + 2, close);
            int contentOffset = open + 2;
            if (content.startsWith("-")) {
                content = content.substring(1);
                contentOffset++;
                text = text.stripTrailing();
            }
            addText(text, pos);
            if (content.endsWith("-")) {
                content = content.substring(0, content.length() - 1);
                stripNext = true;
            }
            if (kind != '#') {
                raws.add(new Raw(kind == '{' ? RawKind.OUTPUT : RawKind.TAG, content, open, contentOffset));
            }
            pos = close + 2;
        }
    }

    private int nextOpen(int from) {
        int i = src.indexOf('{', from);
        while (i >= 0 && i + 1 < src.length()) {
            char c = src.charAt(i + 1);
            if (c == '{' || c == '%' || c == '#') {
                return i;
            }
            i = src.indexOf('{', i + 1);
        }
        return -1;
    }

    private void addText(String text, int offset) {
        if (!text.isEmpty()) {
            raws.add(new Raw(RawKind.TEXT, text, offset, offset));
        }
    }

    // ---- stage 2: blocks ----

    /** Parses nodes until a tag whose keyword is in {@code stops}; {@link #stop} then holds it, else it is null. */
    private List<Node> nodes(Set<String> stops) {
        List<Node> out = new ArrayList<>();
        stop = null;
        while (index < raws.size()) {
            Raw raw = raws.get(index++);
            switch (raw.kind()) {
                case TEXT -> out.add(new Node.Text(raw.text()));
                case OUTPUT -> out.add(output(raw));
                case TAG -> {
                    List<Tk> toks = lex(raw.text(), raw.contentOffset());
                    int end = raw.contentOffset() + raw.text().length();
                    if (toks.isEmpty()) {
                        throw source.syntax("empty tag", raw.contentOffset());
                    }
                    Tk keyword = toks.get(0);
                    if (stops.contains(keyword.text()) && keyword.type() == Type.NAME) {
                        stop = new Stop(toks, raw.open(), end);
                        return out;
                    }
                    out.add(switch (keyword.type() == Type.NAME ? keyword.text() : "") {
                        case "if" -> ifNode(toks, raw.open(), end);
                        case "for" -> forNode(toks, raw.open(), end);
                        case "elif", "else", "endif", "endfor" ->
                                throw source.syntax("unexpected '" + keyword.text() + "'", keyword.offset());
                        default -> throw source.syntax("unknown tag " + keyword.describe(), keyword.offset());
                    });
                }
            }
        }
        stop = null; // ran out of input: a nested block's stop tag must not leak out as ours
        return out;
    }

    private Node ifNode(List<Tk> toks, int open, int end) {
        ExprParser cond = new ExprParser(toks, 1, end);
        Cond c = cond.parseCond();
        cond.expectEnd();
        List<Node> then = nodes(Set.of("elif", "else", "endif"));
        if (stop == null) {
            throw source.syntax("unclosed 'if'", open);
        }
        Stop s = stop;
        String kw = s.tokens().get(0).text();
        if (kw.equals("elif")) {
            return new Node.If(c, then, List.of(ifNode(s.tokens(), s.open(), s.end())));
        }
        if (kw.equals("else")) {
            expectNoArguments(s);
            List<Node> otherwise = nodes(Set.of("endif"));
            if (stop == null) {
                throw source.syntax("unclosed 'if'", open);
            }
            expectNoArguments(stop);
            return new Node.If(c, then, otherwise);
        }
        expectNoArguments(s);
        return new Node.If(c, then, List.of());
    }

    private Node forNode(List<Tk> toks, int open, int end) {
        ExprParser p = new ExprParser(toks, 1, end);
        Tk var = p.next("loop variable name");
        if (var.type() != Type.NAME || var.text().contains(".") || RESERVED.contains(var.text())) {
            throw source.syntax("expected loop variable name, found " + var.describe(), var.offset());
        }
        Tk in = p.next("'in'");
        if (!in.isWord("in")) {
            throw source.syntax("expected 'in', found " + in.describe(), in.offset());
        }
        Operand operand = p.operand();
        p.expectEnd();
        if (!(operand instanceof Operand.Path path)) {
            throw source.syntax("loop source must be a variable path", in.offset());
        }
        List<Node> body = nodes(Set.of("endfor"));
        if (stop == null) {
            throw source.syntax("unclosed 'for'", open);
        }
        expectNoArguments(stop);
        return new Node.For(var.text(), path, body);
    }

    private void expectNoArguments(Stop s) {
        if (s.tokens().size() > 1) {
            Tk extra = s.tokens().get(1);
            throw source.syntax("unexpected " + extra.describe(), extra.offset());
        }
    }

    private Node output(Raw raw) {
        List<Tk> toks = lex(raw.text(), raw.contentOffset());
        ExprParser p = new ExprParser(toks, 0, raw.contentOffset() + raw.text().length());
        Operand operand = p.operand();
        int offset = toks.get(0).offset();
        List<Filter> filters = new ArrayList<>();
        while (p.peekType() == Type.PIPE) {
            p.next("filter");
            Tk name = p.next("filter name");
            if (name.type() != Type.NAME || name.text().contains(".")) {
                throw source.syntax("expected filter name, found " + name.describe(), name.offset());
            }
            if (!FILTERS.contains(name.text())) {
                throw source.syntax("unknown filter '" + name.text() + "'", name.offset());
            }
            Object arg = null;
            if (name.text().equals("default")) {
                Tk a = p.peek();
                if (a == null || (a.type() != Type.STRING && a.type() != Type.INT)) {
                    throw source.syntax("filter 'default' requires a string or integer argument", name.offset());
                }
                p.next("argument");
                arg = a.type() == Type.INT ? (Object) Long.valueOf(a.text()) : a.text();
            }
            filters.add(new Filter(name.text(), arg));
        }
        p.expectEnd();
        return new Node.Output(operand, List.copyOf(filters), offset);
    }

    // ---- tag lexer ----

    private List<Tk> lex(String s, int base) {
        List<Tk> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                i = scanName(s, i, base);
                out.add(new Tk(Type.NAME, s.substring(start, i), base + start));
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int start = i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                String digits = s.substring(start, i);
                try {
                    Long.parseLong(digits);
                } catch (NumberFormatException e) {
                    throw source.syntax("integer out of range: " + digits, base + start);
                }
                out.add(new Tk(Type.INT, digits, base + start));
            } else if (c == '"' || c == '\'') {
                i = scanString(s, i, base, out);
            } else if (c == '|') {
                out.add(new Tk(Type.PIPE, "|", base + i++));
            } else if (s.startsWith("==", i)) {
                out.add(new Tk(Type.EQ, "==", base + i));
                i += 2;
            } else if (s.startsWith("!=", i)) {
                out.add(new Tk(Type.NE, "!=", base + i));
                i += 2;
            } else {
                throw source.syntax("unexpected character '" + c + "'", base + i);
            }
        }
        return out;
    }

    private int scanName(String s, int from, int base) {
        int i = from;
        while (true) {
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            if (i < s.length() && s.charAt(i) == '.') {
                int next = i + 1;
                if (next >= s.length() || !(Character.isLetterOrDigit(s.charAt(next)) || s.charAt(next) == '_')) {
                    throw source.syntax("invalid path: segment expected after '.'", base + i);
                }
                i = next;
            } else {
                return i;
            }
        }
    }

    private int scanString(String s, int from, int base, List<Tk> out) {
        char quote = s.charAt(from);
        StringBuilder sb = new StringBuilder();
        int i = from + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == quote) {
                out.add(new Tk(Type.STRING, sb.toString(), base + from));
                return i + 1;
            }
            if (c == '\\' && i + 1 < s.length()) {
                char e = s.charAt(i + 1);
                sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        throw source.syntax("unterminated string", base + from);
    }

    // ---- expression parser ----

    private final class ExprParser {
        private final List<Tk> toks;
        private final int endOffset;
        private int pos;

        ExprParser(List<Tk> toks, int start, int endOffset) {
            this.toks = toks;
            this.pos = start;
            this.endOffset = endOffset;
        }

        Tk peek() {
            return pos < toks.size() ? toks.get(pos) : null;
        }

        Type peekType() {
            Tk t = peek();
            return t == null ? null : t.type();
        }

        Tk next(String what) {
            Tk t = peek();
            if (t == null) {
                throw source.syntax("expected " + what, endOffset);
            }
            pos++;
            return t;
        }

        void expectEnd() {
            Tk t = peek();
            if (t != null) {
                throw source.syntax("unexpected " + t.describe(), t.offset());
            }
        }

        Cond parseCond() {
            Cond left = parseAnd();
            while (peek() != null && peek().isWord("or")) {
                pos++;
                left = new Cond.Or(left, parseAnd());
            }
            return left;
        }

        private Cond parseAnd() {
            Cond left = parseNot();
            while (peek() != null && peek().isWord("and")) {
                pos++;
                left = new Cond.And(left, parseNot());
            }
            return left;
        }

        private Cond parseNot() {
            if (peek() != null && peek().isWord("not")) {
                pos++;
                return new Cond.Not(parseNot());
            }
            Operand left = operand();
            Tk t = peek();
            if (t != null && (t.type() == Type.EQ || t.type() == Type.NE)) {
                pos++;
                return new Cond.Cmp(left, t.type() == Type.EQ, operand());
            }
            return new Cond.Truthy(left);
        }

        Operand operand() {
            Tk t = peek();
            if (t == null) {
                throw source.syntax("expected operand", endOffset);
            }
            switch (t.type()) {
                case NAME -> {
                    if (RESERVED.contains(t.text())) {
                        throw source.syntax("expected operand, found " + t.describe(), t.offset());
                    }
                    pos++;
                    return new Operand.Path(t.text().split("\\."), t.offset());
                }
                case STRING -> {
                    pos++;
                    return new Operand.Str(t.text());
                }
                case INT -> {
                    pos++;
                    return new Operand.Int(Long.parseLong(t.text()));
                }
                default -> throw source.syntax("expected operand, found " + t.describe(), t.offset());
            }
        }
    }
}
