package ae.mdlite;

import ae.mdlite.Block.CodeBlock;
import ae.mdlite.Block.Heading;
import ae.mdlite.Block.ListBlock;
import ae.mdlite.Block.Paragraph;
import ae.mdlite.Block.Quote;
import ae.mdlite.Block.Rule;
import ae.mdlite.Inline.Code;
import ae.mdlite.Inline.Emph;
import ae.mdlite.Inline.Link;
import ae.mdlite.Inline.Strong;
import ae.mdlite.Inline.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, strict Markdown subset: parser (to a sealed-interface AST) and HTML renderer.
 *
 * <p>Raw HTML is never passed through, link schemes are allow-listed, and nesting is capped so hostile input
 * yields a {@link MarkdownException} rather than a {@link StackOverflowError}.
 */
public final class Markdown {
    private static final int MAX_QUOTE_DEPTH = 50;
    private static final int MAX_INLINE_DEPTH = 100;
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*]|\\d{1,9}\\.)( +)(.*)$");
    private static final Pattern FENCE_LANG = Pattern.compile("[A-Za-z0-9_+-]*");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final String ESCAPABLE = "\\*_`[]()#>";

    private Markdown() { }

    /** Parses {@code src}; throws {@link MarkdownException} on a malformed or disallowed construct. */
    public static Document parse(String src) {
        Objects.requireNonNull(src, "src");
        String[] raw = src.replace("\r\n", "\n").split("\n", -1);
        List<Ln> lines = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            lines.add(new Ln(raw[i], i + 1, 1));
        }
        return new Document(List.copyOf(blocks(lines, 0)));
    }

    /** Parses and renders {@code src} to HTML. */
    public static String toHtml(String src) {
        return render(parse(src));
    }

    // ---- block parsing ------------------------------------------------

    /** One source line (or the tail of one) with the 1-based column of its first char. */
    private record Ln(String s, int no, int col) {
        Ln drop(int n) {
            return new Ln(s.substring(n), no, col + n);
        }
    }

    private static MarkdownException err(int line, int col, String msg) {
        return new MarkdownException(line, col, msg);
    }

    private static List<Block> blocks(List<Ln> lines, int depth) {
        List<Block> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            Ln ln = lines.get(i);
            String s = ln.s();
            if (s.isBlank()) {
                i++;
            } else if (s.startsWith("```")) {
                i = fence(lines, i, out);
            } else if (headingLevel(s) > 0) {
                int h = headingLevel(s);
                int k = h;
                while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
                    k++;
                }
                Ln body = new Ln(s.substring(k).stripTrailing(), ln.no(), ln.col() + k);
                out.add(new Heading(h, inlines(List.of(body))));
                i++;
            } else if (isRule(s)) {
                out.add(new Rule());
                i++;
            } else if (s.startsWith(">")) {
                if (depth >= MAX_QUOTE_DEPTH) {
                    throw err(ln.no(), ln.col(), "block quotes nested too deeply");
                }
                List<Ln> inner = new ArrayList<>();
                while (i < lines.size() && lines.get(i).s().startsWith(">")) {
                    Ln q = lines.get(i);
                    inner.add(q.drop(q.s().length() > 1 && q.s().charAt(1) == ' ' ? 2 : 1));
                    i++;
                }
                out.add(new Quote(List.copyOf(blocks(inner, depth + 1))));
            } else if (LIST_ITEM.matcher(s).matches()) {
                i = list(lines, i, out);
            } else {
                i = paragraph(lines, i, out);
            }
        }
        return out;
    }

    private static int fence(List<Ln> lines, int start, List<Block> out) {
        Ln open = lines.get(start);
        String info = open.s().substring(3);
        String lang = info.strip();
        if (!FENCE_LANG.matcher(lang).matches()) {
            int lead = info.length() - info.stripLeading().length();
            throw err(open.no(), open.col() + 3 + lead, "invalid code fence language");
        }
        StringBuilder text = new StringBuilder();
        for (int i = start + 1; i < lines.size(); i++) {
            String s = lines.get(i).s();
            if (s.stripTrailing().equals("```")) {
                out.add(new CodeBlock(lang, text.toString()));
                return i + 1;
            }
            text.append(s).append('\n');
        }
        throw err(open.no(), open.col(), "unclosed code fence");
    }

    private static int list(List<Ln> lines, int start, List<Block> out) {
        boolean ordered = Character.isDigit(lines.get(start).s().charAt(0));
        List<List<Inline>> items = new ArrayList<>();
        int i = start;
        while (i < lines.size()) {
            Ln ln = lines.get(i);
            Matcher m = LIST_ITEM.matcher(ln.s());
            if (!m.matches() || isRule(ln.s()) || Character.isDigit(ln.s().charAt(0)) != ordered) {
                break;
            }
            List<Ln> itemLines = new ArrayList<>();
            itemLines.add(ln.drop(ln.s().length() - m.group(2).length()));
            i++;
            while (i < lines.size() && lines.get(i).s().startsWith("  ") && !lines.get(i).s().isBlank()) {
                Ln c = lines.get(i);
                int lead = c.s().length() - c.s().stripLeading().length();
                itemLines.add(c.drop(lead));
                i++;
            }
            items.add(inlines(itemLines));
        }
        out.add(new ListBlock(ordered, List.copyOf(items)));
        return i;
    }

    private static int paragraph(List<Ln> lines, int start, List<Block> out) {
        List<Ln> para = new ArrayList<>();
        int i = start;
        while (i < lines.size()) {
            Ln ln = lines.get(i);
            if (ln.s().isBlank() || (i > start && startsBlock(ln.s()))) {
                break;
            }
            int lead = ln.s().length() - ln.s().stripLeading().length();
            Ln t = ln.drop(lead);
            para.add(new Ln(t.s().stripTrailing(), t.no(), t.col()));
            i++;
        }
        out.add(new Paragraph(inlines(para)));
        return i;
    }

    private static boolean startsBlock(String s) {
        return s.startsWith("```") || headingLevel(s) > 0 || isRule(s) || s.startsWith(">")
                || LIST_ITEM.matcher(s).matches();
    }

    /** Returns 1..6 when {@code s} is an ATX heading line, else 0. */
    private static int headingLevel(String s) {
        int h = 0;
        while (h < s.length() && s.charAt(h) == '#') {
            h++;
        }
        boolean ok = h >= 1 && h <= 6 && (h == s.length() || s.charAt(h) == ' ' || s.charAt(h) == '\t');
        return ok ? h : 0;
    }

    private static boolean isRule(String s) {
        String t = s.strip();
        if (t.length() < 3) {
            return false;
        }
        char c = t.charAt(0);
        if (c != '-' && c != '*' && c != '_') {
            return false;
        }
        for (int i = 1; i < t.length(); i++) {
            if (t.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }

    // ---- inline parsing -----------------------------------------------

    /** Joined source text with a (line, column) for every char. */
    private static final class Src {
        final char[] t;
        final int[] line;
        final int[] col;
        final int[] match;
        final int[] nextTick;
        final Map<Long, Integer> failed = new HashMap<>();

        Src(List<Ln> lines) {
            int n = 0;
            for (Ln l : lines) {
                n += l.s().length() + 1;
            }
            n = Math.max(0, n - 1);
            t = new char[n];
            line = new int[n];
            col = new int[n];
            int p = 0;
            for (int k = 0; k < lines.size(); k++) {
                Ln l = lines.get(k);
                for (int j = 0; j < l.s().length(); j++, p++) {
                    t[p] = l.s().charAt(j);
                    line[p] = l.no();
                    col[p] = l.col() + j;
                }
                if (k < lines.size() - 1) {
                    t[p] = '\n';
                    line[p] = l.no();
                    col[p] = l.col() + l.s().length();
                    p++;
                }
            }
            match = new int[n];
            java.util.Arrays.fill(match, -1);
            int[] stack = new int[n + 1];
            int sp = 0;
            for (int i = 0; i < n; i++) {
                if (t[i] == '\\' && i + 1 < n && ESCAPABLE.indexOf(t[i + 1]) >= 0) {
                    i++;
                } else if (t[i] == '[') {
                    stack[sp++] = i;
                } else if (t[i] == ']' && sp > 0) {
                    match[stack[--sp]] = i;
                }
            }
            nextTick = new int[n + 1];
            nextTick[n] = Integer.MAX_VALUE;
            for (int i = n - 1; i >= 0; i--) {
                nextTick[i] = t[i] == '`' ? i : nextTick[i + 1];
            }
        }

        MarkdownException error(int idx, String msg) {
            return new MarkdownException(line[idx], col[idx], msg);
        }
    }

    private static List<Inline> inlines(List<Ln> lines) {
        Src s = new Src(lines);
        return List.copyOf(inlines(s, 0, s.t.length, 0));
    }

    private static List<Inline> inlines(Src s, int from, int to, int depth) {
        List<Inline> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        char[] t = s.t;
        int i = from;
        while (i < to) {
            char c = t[i];
            if (c == '\\' && i + 1 < to && ESCAPABLE.indexOf(t[i + 1]) >= 0) {
                text.append(t[i + 1]);
                i += 2;
            } else if (c == '`') {
                int j = s.nextTick[i + 1];
                if (j < to) {
                    flush(out, text);
                    out.add(new Code(new String(t, i + 1, j - i - 1)));
                    i = j + 1;
                } else {
                    text.append(c);
                    i++;
                }
            } else if (c == '*') {
                int d = i + 1 < to && t[i + 1] == '*' ? 2 : 1;
                int inner = i + d;
                int close = inner < to && !Character.isWhitespace(t[inner]) && t[inner] != '*'
                        ? findClose(s, inner, to, d) : -1;
                if (close < 0) {
                    text.append("*".repeat(d));
                    i += d;
                } else {
                    checkDepth(s, i, depth);
                    flush(out, text);
                    List<Inline> kids = inlines(s, inner, close, depth + 1);
                    out.add(d == 2 ? new Strong(kids) : new Emph(kids));
                    i = close + d;
                }
            } else if (c == '[' && s.match[i] >= 0 && s.match[i] < to && s.match[i] + 1 < to
                    && t[s.match[i] + 1] == '(') {
                int m = s.match[i];
                int close = m + 2;
                while (close < to && t[close] != ')') {
                    close++;
                }
                if (close >= to) {
                    throw s.error(i, "unclosed link url");
                }
                String url = new String(t, m + 2, close - m - 2).strip();
                checkUrl(s, i, url);
                checkDepth(s, i, depth);
                flush(out, text);
                out.add(new Link(List.copyOf(inlines(s, i + 1, m, depth + 1)), url));
                i = close + 1;
            } else {
                text.append(c);
                i++;
            }
        }
        flush(out, text);
        return out;
    }

    private static void checkDepth(Src s, int idx, int depth) {
        if (depth + 1 > MAX_INLINE_DEPTH) {
            throw s.error(idx, "inline elements nested too deeply");
        }
    }

    private static void flush(List<Inline> out, StringBuilder text) {
        if (text.length() > 0) {
            out.add(new Text(text.toString()));
            text.setLength(0);
        }
    }

    /**
     * Finds the closing delimiter ({@code d} stars) for an opener whose content starts at {@code from}.
     * A failed search is remembered per (delimiter, end) so repeated openers stay linear overall.
     */
    private static int findClose(Src s, int from, int to, int d) {
        long key = (long) d * (s.t.length + 1L) + to;
        Integer failedFrom = s.failed.get(key);
        if (failedFrom != null && from >= failedFrom) {
            return -1;
        }
        char[] t = s.t;
        int k = from;
        while (k < to) {
            char c = t[k];
            if (c == '\\' && k + 1 < to && ESCAPABLE.indexOf(t[k + 1]) >= 0) {
                k += 2;
            } else if (c == '`') {
                int j = s.nextTick[k + 1];
                k = j < to ? j + 1 : k + 1;
            } else if (c == '*') {
                boolean dbl = k + 1 < to && t[k + 1] == '*';
                if (d == 2 && dbl && k > from && !Character.isWhitespace(t[k - 1])) {
                    return k;
                }
                if (d == 1 && !dbl && k > from && !Character.isWhitespace(t[k - 1])) {
                    return k;
                }
                k += dbl ? 2 : 1;
            } else {
                k++;
            }
        }
        s.failed.merge(key, from, Math::min);
        return -1;
    }

    private static void checkUrl(Src s, int idx, String url) {
        for (int i = 0; i < url.length(); i++) {
            if (Character.isWhitespace(url.charAt(i)) || Character.isISOControl(url.charAt(i))) {
                throw s.error(idx, "whitespace or control character in url");
            }
        }
        if (url.startsWith("//")) {
            throw s.error(idx, "protocol-relative url not allowed");
        }
        Matcher m = SCHEME.matcher(url);
        if (m.find()) {
            String scheme = m.group(1).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) {
                throw s.error(idx, "url scheme not allowed: " + scheme);
            }
        }
    }

    // ---- rendering ----------------------------------------------------

    /** Renders a document to HTML; every text, code and attribute value is escaped. */
    public static String render(Document doc) {
        StringBuilder sb = new StringBuilder();
        renderBlocks(doc.blocks(), sb);
        return sb.toString();
    }

    private static void renderBlocks(List<Block> blocks, StringBuilder sb) {
        for (Block b : blocks) {
            switch (b) {
                case Heading h -> {
                    sb.append("<h").append(h.level()).append('>');
                    renderInlines(h.inlines(), sb);
                    sb.append("</h").append(h.level()).append(">\n");
                }
                case Paragraph p -> {
                    sb.append("<p>");
                    renderInlines(p.inlines(), sb);
                    sb.append("</p>\n");
                }
                case CodeBlock c -> {
                    sb.append("<pre><code");
                    if (!c.lang().isEmpty()) {
                        sb.append(" class=\"language-").append(c.lang()).append('"');
                    }
                    sb.append('>');
                    escape(c.text(), sb);
                    sb.append("</code></pre>\n");
                }
                case Quote q -> {
                    sb.append("<blockquote>\n");
                    renderBlocks(q.blocks(), sb);
                    sb.append("</blockquote>\n");
                }
                case ListBlock l -> {
                    String tag = l.ordered() ? "ol" : "ul";
                    sb.append('<').append(tag).append(">\n");
                    for (List<Inline> item : l.items()) {
                        sb.append("<li>");
                        renderInlines(item, sb);
                        sb.append("</li>\n");
                    }
                    sb.append("</").append(tag).append(">\n");
                }
                case Rule r -> sb.append("<hr>\n");
            }
        }
    }

    private static void renderInlines(List<Inline> inlines, StringBuilder sb) {
        for (Inline in : inlines) {
            switch (in) {
                case Text t -> escape(t.value(), sb);
                case Emph e -> {
                    sb.append("<em>");
                    renderInlines(e.children(), sb);
                    sb.append("</em>");
                }
                case Strong st -> {
                    sb.append("<strong>");
                    renderInlines(st.children(), sb);
                    sb.append("</strong>");
                }
                case Code c -> {
                    sb.append("<code>");
                    escape(c.value(), sb);
                    sb.append("</code>");
                }
                case Link l -> {
                    sb.append("<a href=\"");
                    escape(l.url(), sb);
                    sb.append("\">");
                    renderInlines(l.text(), sb);
                    sb.append("</a>");
                }
            }
        }
    }

    private static void escape(String s, StringBuilder sb) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
    }
}
