package ae.shellx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Expands a command line into words with a documented subset of bash semantics (pathname expansion
 * off): quote lexing, brace expansion, parameter expansion, word splitting and quote removal.
 *
 * <p>Internally a word is a list of {@link Ch}: a character, a kind that says how later stages treat
 * it, and the index it came from in the input line (for error positions).
 */
public final class WordExpander {
    /** Maximum number of words brace expansion may produce. */
    static final int MAX_WORDS = 10_000;

    // Kinds of characters. Table of what each stage may do with them:
    //   kind   brace-special  expands $   splits on whitespace
    //   PLAIN  yes            yes         no (raw whitespace never occurs outside ${...} operands)
    //   DQ     no             yes         no
    //   LIT    no             no          no   (single quoted, escaped, or finished output of a quoted expansion)
    //   SPLIT  no             no          yes  (result of an unquoted expansion)
    //   MARK   zero-width marker proving the word was quoted ("" keeps an empty word alive)
    private enum Kind { PLAIN, DQ, LIT, SPLIT, MARK }

    private record Ch(char c, Kind kind, int pos) {
        boolean isPlain(char x) {
            return kind == Kind.PLAIN && c == x;
        }
    }

    private WordExpander() { }

    /**
     * Expands one line.
     *
     * @param line the command line
     * @param env variable values; absent names are unset
     * @return the resulting words
     * @throws ShellSyntaxException on unterminated quotes, a trailing backslash or a bad {@code ${}}
     * @throws IllegalStateException if brace expansion would exceed {@value #MAX_WORDS} words
     */
    public static List<String> expand(String line, Map<String, String> env) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(env, "env");
        List<String> out = new ArrayList<>();
        for (List<Ch> word : lex(line)) {
            List<List<Ch>> braced = new ArrayList<>();
            expandBraces(word, braced);
            if (braced.size() > MAX_WORDS) {
                throw new IllegalStateException("too many words");
            }
            for (List<Ch> b : braced) {
                splitInto(expandParams(b, env, Kind.PLAIN), out);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- lexing

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n';
    }

    private static List<List<Ch>> lex(String s) {
        List<List<Ch>> words = new ArrayList<>();
        List<Ch> cur = null;
        int paramDepth = 0; // nesting of unquoted ${ ... } (whitespace inside does not split)
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (isBlank(c) && paramDepth == 0) {
                cur = null;
                i++;
                continue;
            }
            if (cur == null) {
                cur = new ArrayList<>();
                words.add(cur);
            }
            if (c == '\'') {
                int end = s.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new ShellSyntaxException("unterminated single quote", i);
                }
                cur.add(new Ch('\0', Kind.MARK, i));
                for (int k = i + 1; k < end; k++) {
                    cur.add(new Ch(s.charAt(k), Kind.LIT, k));
                }
                i = end + 1;
            } else if (c == '"') {
                i = lexDouble(s, i, cur);
            } else if (c == '\\') {
                if (i + 1 >= s.length()) {
                    throw new ShellSyntaxException("dangling backslash", i);
                }
                cur.add(new Ch(s.charAt(i + 1), Kind.LIT, i + 1));
                i += 2;
            } else if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '{') {
                cur.add(new Ch('$', Kind.PLAIN, i));
                cur.add(new Ch('{', Kind.PLAIN, i + 1));
                paramDepth++;
                i += 2;
            } else if (c == '}' && paramDepth > 0) {
                cur.add(new Ch('}', Kind.PLAIN, i));
                paramDepth--;
                i++;
            } else {
                cur.add(new Ch(c, Kind.PLAIN, i));
                i++;
            }
        }
        if (paramDepth > 0) {
            // report the outermost unterminated ${: re-scan to find it
            throw new ShellSyntaxException("unterminated ${", firstOpenParam(s));
        }
        return words;
    }

    /** Position of the outermost unquoted {@code ${} that never closes. */
    private static int firstOpenParam(String s) {
        List<Integer> open = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                i = s.indexOf('\'', i + 1) + 1;
            } else if (c == '"') {
                i = skipDouble(s, i);
            } else if (c == '\\') {
                i += 2;
            } else if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '{') {
                open.add(i);
                i += 2;
            } else {
                if (c == '}' && !open.isEmpty()) {
                    open.remove(open.size() - 1);
                }
                i++;
            }
        }
        return open.get(0);
    }

    private static int skipDouble(String s, int start) {
        int i = start + 1;
        while (i < s.length() && s.charAt(i) != '"') {
            i += s.charAt(i) == '\\' ? 2 : 1;
        }
        return i + 1;
    }

    /** Lexes a double-quoted section starting at {@code start}; returns the index after it. */
    private static int lexDouble(String s, int start, List<Ch> cur) {
        cur.add(new Ch('\0', Kind.MARK, start));
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                cur.add(new Ch('\0', Kind.MARK, i)); // closing mark: ends a $NAME that ran up to the quote
                return i + 1;
            }
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '"' || n == '\\' || n == '$') {
                    cur.add(new Ch(n, Kind.LIT, i + 1));
                    i += 2;
                    continue;
                }
            }
            cur.add(new Ch(c, Kind.DQ, i));
            i++;
        }
        throw new ShellSyntaxException("unterminated double quote", start);
    }

    // ------------------------------------------------------- brace expansion

    private static void expandBraces(List<Ch> w, List<List<Ch>> out) {
        int n = w.size();
        int i = 0;
        while (i < n) {
            Ch ch = w.get(i);
            if (ch.isPlain('$') && i + 1 < n && w.get(i + 1).isPlain('{')) {
                i = closeBraceExact(w, i + 1) + 1; // ${...} is never brace-expanded
                continue;
            }
            if (ch.isPlain('{')) {
                int close = matchingClose(w, i);
                if (close > 0) {
                    List<List<Ch>> alts = alternatives(w.subList(i + 1, close));
                    if (alts != null) {
                        List<List<Ch>> suffixes = new ArrayList<>();
                        expandBraces(new ArrayList<>(w.subList(close + 1, n)), suffixes);
                        List<List<Ch>> expanded = new ArrayList<>();
                        for (List<Ch> alt : alts) {
                            expandBraces(alt, expanded);
                        }
                        for (List<Ch> e : expanded) {
                            for (List<Ch> sfx : suffixes) {
                                List<Ch> merged = new ArrayList<>(w.subList(0, i));
                                merged.addAll(e);
                                merged.addAll(sfx);
                                out.add(merged);
                                if (out.size() > MAX_WORDS) {
                                    throw new IllegalStateException("too many words");
                                }
                            }
                        }
                        return;
                    }
                }
            }
            i++;
        }
        out.add(w);
    }

    /** Index of the PLAIN '}' matching the '{' at {@code open} by plain nesting, or the last index if none. */
    private static int closeBraceExact(List<Ch> w, int open) {
        int depth = 0;
        for (int i = open; i < w.size(); i++) {
            if (w.get(i).isPlain('{')) {
                depth++;
            } else if (w.get(i).isPlain('}')) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return w.size() - 1;
    }

    /**
     * Index of the PLAIN '}' that closes the brace group opened at {@code open}, or -1. Like bash, a
     * '}' at nesting level 0 closes the group only if a level-0 comma came before it or the text
     * between the braces is an integer sequence; otherwise that '}' is ordinary text and scanning
     * goes on (so {@code {a},b}} is the list "a}" and "b").
     */
    private static int matchingClose(List<Ch> w, int open) {
        int depth = 0;
        int commas = 0;
        for (int i = open + 1; i < w.size(); i++) {
            Ch c = w.get(i);
            if (c.isPlain('{')) {
                depth++;
            } else if (c.isPlain('}')) {
                if (depth > 0) {
                    depth--;
                } else if (commas > 0 || sequence(w.subList(open + 1, i)) != null) {
                    return i;
                }
            } else if (depth == 0 && c.isPlain(',')) {
                commas++;
            }
        }
        return -1;
    }

    /** Alternatives for the text between braces, or null if it is neither a list nor a sequence. */
    private static List<List<Ch>> alternatives(List<Ch> body) {
        List<List<Ch>> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < body.size(); i++) {
            Ch c = body.get(i);
            if (c.isPlain('{')) {
                depth++;
            } else if (c.isPlain('}')) {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0 && c.isPlain(',')) {
                parts.add(new ArrayList<>(body.subList(from, i)));
                from = i + 1;
            }
        }
        if (!parts.isEmpty()) {
            parts.add(new ArrayList<>(body.subList(from, body.size())));
            return parts;
        }
        return sequence(body);
    }

    private static List<List<Ch>> sequence(List<Ch> body) {
        StringBuilder sb = new StringBuilder();
        for (Ch c : body) {
            if (c.kind() != Kind.PLAIN) {
                return null;
            }
            sb.append(c.c());
        }
        String[] p = sb.toString().split("\\.\\.", -1);
        if ((p.length != 2 && p.length != 3) || !isInt(p[0]) || !isInt(p[1]) || (p.length == 3 && !isInt(p[2]))) {
            return null;
        }
        long a = Long.parseLong(p[0]);
        long b = Long.parseLong(p[1]);
        long step = p.length == 3 ? Math.abs(Long.parseLong(p[2])) : 1;
        if (step == 0) {
            step = 1;
        }
        boolean pad = hasLeadingZero(p[0]) || hasLeadingZero(p[1]);
        int width = pad ? Math.max(p[0].length(), p[1].length()) : 0;
        List<List<Ch>> out = new ArrayList<>();
        long v = a;
        while (a <= b ? v <= b : v >= b) {
            out.add(toChs(format(v, width)));
            if (out.size() > MAX_WORDS) {
                throw new IllegalStateException("too many words");
            }
            v += a <= b ? step : -step;
        }
        return out;
    }

    private static boolean isInt(String s) {
        return s.matches("-?[0-9]{1,15}");
    }

    private static boolean hasLeadingZero(String s) {
        String d = s.startsWith("-") ? s.substring(1) : s;
        return d.length() > 1 && d.charAt(0) == '0';
    }

    private static String format(long v, int width) {
        String digits = Long.toString(Math.abs(v));
        int target = width - (v < 0 ? 1 : 0);
        StringBuilder sb = new StringBuilder(v < 0 ? "-" : "");
        for (int k = digits.length(); k < target; k++) {
            sb.append('0');
        }
        return sb.append(digits).toString();
    }

    private static List<Ch> toChs(String s) {
        List<Ch> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            out.add(new Ch(s.charAt(i), Kind.PLAIN, 0)); // textual: may extend a preceding $NAME
        }
        return out;
    }

    // ----------------------------------------------------- parameter expansion

    /**
     * Expands parameters in {@code in}. Output kinds: LIT (no more splitting), SPLIT (splittable),
     * PLAIN (raw unquoted text, never whitespace), MARK.
     *
     * @param ctx kind that raw PLAIN text of an operand word takes (PLAIN at the top level)
     */
    private static List<Ch> expandParams(List<Ch> in, Map<String, String> env, Kind ctx) {
        List<Ch> out = new ArrayList<>();
        int n = in.size();
        int i = 0;
        while (i < n) {
            Ch ch = in.get(i);
            boolean expands = (ch.kind() == Kind.PLAIN || ch.kind() == Kind.DQ) && ch.c() == '$';
            if (!expands) {
                out.add(ch.kind() == Kind.DQ ? new Ch(ch.c(), Kind.LIT, ch.pos()) : ch);
                i++;
                continue;
            }
            boolean quoted = ch.kind() == Kind.DQ;
            Kind result = quoted ? Kind.LIT : Kind.SPLIT;
            if (i + 1 < n && in.get(i + 1).c() == '{' && isRaw(in.get(i + 1))) {
                int close = closeParam(in, i + 1);
                if (close < 0) {
                    throw new ShellSyntaxException("unterminated ${", ch.pos());
                }
                expandBraced(in.subList(i + 2, close), ch.pos(), env, result, out);
                i = close + 1;
            } else {
                int j = i + 1;
                while (j < n && isRaw(in.get(j)) && isNameChar(in.get(j).c(), j == i + 1)) {
                    j++;
                }
                if (j == i + 1 && i + 1 < n && isRaw(in.get(i + 1)) && Character.isDigit(in.get(i + 1).c())) {
                    i += 2; // positional parameter $0-$9: no positional arguments exist, so it is empty
                } else if (j == i + 1) {
                    out.add(new Ch('$', quoted ? Kind.LIT : Kind.PLAIN, ch.pos()));
                    i++;
                } else {
                    append(out, env.getOrDefault(text(in.subList(i + 1, j)), ""), result, ch.pos());
                    i = j;
                }
            }
        }
        return out;
    }

    private static boolean isRaw(Ch c) {
        return c.kind() == Kind.PLAIN || c.kind() == Kind.DQ;
    }

    private static boolean isNameChar(char c, boolean first) {
        boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
        return letter || (!first && c >= '0' && c <= '9');
    }

    private static String text(List<Ch> chs) {
        StringBuilder sb = new StringBuilder();
        for (Ch c : chs) {
            sb.append(c.c());
        }
        return sb.toString();
    }

    private static void append(List<Ch> out, String s, Kind kind, int pos) {
        for (int k = 0; k < s.length(); k++) {
            out.add(new Ch(s.charAt(k), kind, pos));
        }
    }

    /** Index of the raw '}' closing the ${ whose '{' is at {@code open}, or -1. */
    private static int closeParam(List<Ch> in, int open) {
        int depth = 0;
        for (int i = open; i < in.size(); i++) {
            Ch c = in.get(i);
            if (!isRaw(c)) {
                continue;
            }
            if (c.c() == '{') {
                depth++;
            } else if (c.c() == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void expandBraced(List<Ch> body, int pos, Map<String, String> env, Kind result,
                                     List<Ch> out) {
        boolean length = !body.isEmpty() && body.get(0).c() == '#' && isRaw(body.get(0));
        int start = length ? 1 : 0;
        int j = start;
        while (j < body.size() && isRaw(body.get(j)) && isNameChar(body.get(j).c(), j == start)) {
            j++;
        }
        if (j == start) {
            throw new ShellSyntaxException("bad substitution", pos);
        }
        String name = text(body.subList(start, j));
        String value = env.get(name);
        if (length) {
            if (j != body.size()) {
                throw new ShellSyntaxException("bad substitution", pos);
            }
            append(out, Integer.toString(value == null ? 0 : value.length()), result, pos);
            return;
        }
        if (j == body.size()) {
            append(out, value == null ? "" : value, result, pos);
            return;
        }
        boolean colon = isRaw(body.get(j)) && body.get(j).c() == ':';
        int op = colon ? j + 1 : j;
        if (op >= body.size() || !isRaw(body.get(op)) || (body.get(op).c() != '-' && body.get(op).c() != '+')) {
            throw new ShellSyntaxException("bad substitution", pos);
        }
        boolean useDefault = body.get(op).c() == '-';
        boolean unsetLike = value == null || (colon && value.isEmpty());
        // "-" substitutes the operand when unset-like; "+" substitutes it when NOT unset-like
        boolean substitute = useDefault == unsetLike;
        if (substitute) {
            for (Ch c : expandParams(body.subList(op + 1, body.size()), env, Kind.PLAIN)) {
                out.add(c.kind() == Kind.PLAIN ? new Ch(c.c(), Kind.SPLIT, c.pos()) : c);
            }
        } else if (useDefault) {
            append(out, value, result, pos);
        }
    }

    // Inside double quotes the lexer emits DQ (never PLAIN) characters, so raw PLAIN operand text is
    // always unquoted and may split.

    // ----------------------------------------------------------- splitting

    private static void splitInto(List<Ch> word, List<String> out) {
        StringBuilder cur = new StringBuilder();
        boolean alive = false;
        for (Ch c : word) {
            if (c.kind() == Kind.MARK) {
                alive = true;
            } else if (c.kind() == Kind.SPLIT && isBlank(c.c())) {
                if (alive || cur.length() > 0) {
                    out.add(cur.toString());
                }
                cur.setLength(0);
                alive = false;
            } else {
                cur.append(c.c());
                alive = true;
            }
        }
        if (alive || cur.length() > 0) {
            out.add(cur.toString());
        }
    }
}
