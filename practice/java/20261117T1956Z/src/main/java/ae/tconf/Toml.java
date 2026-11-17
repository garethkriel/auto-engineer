package ae.tconf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Entry point: parses a documented subset of TOML (see TASK.md) with line/column errors. */
public final class Toml {
    private Toml() {
    }

    /** Parses {@code text}; throws {@link TomlException} on any error. */
    public static Table parse(String text) {
        return new Parser(text).parse();
    }

    private enum Kind { IMPLICIT, EXPLICIT, DOTTED, INLINE }

    private record Key(String name, int pos) {
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 256;
        private static final Pattern INT = Pattern.compile("[+-]?(0|[1-9](_?[0-9])*)");
        private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F](_?[0-9a-fA-F])*");
        private static final Pattern FLOAT = Pattern.compile(
                "[+-]?(0|[1-9](_?[0-9])*)(\\.[0-9](_?[0-9])*([eE][+-]?[0-9](_?[0-9])*)?|[eE][+-]?[0-9](_?[0-9])*)");

        private final String s;
        private int pos;
        private int depth;
        private final Table root = new Table();
        private final Map<Table, Kind> kinds = new IdentityHashMap<>();
        private final Set<Value.Arr> tableArrays = Collections.newSetFromMap(new IdentityHashMap<>());
        private Table current = root;

        Parser(String text) {
            this.s = text;
            kinds.put(root, Kind.EXPLICIT);
        }

        // ---------- errors ----------

        private TomlException error(int at, String msg) {
            int line = 1;
            int lineStart = 0;
            for (int i = 0; i < at && i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    line++;
                    lineStart = i + 1;
                }
            }
            return new TomlException(line, at - lineStart + 1, msg);
        }

        // ---------- cursor helpers ----------

        private boolean eof() {
            return pos >= s.length();
        }

        private char peek() {
            return s.charAt(pos);
        }

        private boolean at(char c) {
            return !eof() && peek() == c;
        }

        private void skipWs() {
            while (!eof() && (peek() == ' ' || peek() == '\t')) {
                pos++;
            }
        }

        private void skipComment() {
            if (at('#')) {
                while (!eof() && peek() != '\n') {
                    pos++;
                }
            }
        }

        /** Skips whitespace, newlines and comments. */
        private void skipBlank() {
            while (!eof()) {
                char c = peek();
                if (c == ' ' || c == '\t' || c == '\n') {
                    pos++;
                } else if (c == '\r' && pos + 1 < s.length() && s.charAt(pos + 1) == '\n') {
                    pos += 2;
                } else if (c == '#') {
                    skipComment();
                } else {
                    return;
                }
            }
        }

        private void endOfLine() {
            skipWs();
            skipComment();
            if (eof()) {
                return;
            }
            if (peek() == '\n') {
                pos++;
            } else if (peek() == '\r' && pos + 1 < s.length() && s.charAt(pos + 1) == '\n') {
                pos += 2;
            } else {
                throw error(pos, "expected end of line");
            }
        }

        // ---------- document ----------

        Table parse() {
            while (true) {
                skipBlank();
                if (eof()) {
                    return root;
                }
                if (peek() == '[') {
                    header();
                } else {
                    keyValue(current);
                }
                endOfLine();
            }
        }

        // ---------- keys ----------

        private List<Key> keyPath() {
            List<Key> path = new ArrayList<>();
            while (true) {
                skipWs();
                path.add(key());
                skipWs();
                if (at('.')) {
                    pos++;
                } else {
                    return path;
                }
            }
        }

        private Key key() {
            int start = pos;
            if (eof()) {
                throw error(pos, "expected key");
            }
            char c = peek();
            if (c == '"') {
                return new Key(basicString(), start);
            }
            if (c == '\'') {
                return new Key(literalString(), start);
            }
            while (!eof() && isBare(peek())) {
                pos++;
            }
            if (pos == start) {
                throw error(start, "expected key");
            }
            return new Key(s.substring(start, pos), start);
        }

        private static boolean isBare(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
        }

        // ---------- headers ----------

        private void header() {
            boolean array = pos + 1 < s.length() && s.charAt(pos + 1) == '[';
            pos += array ? 2 : 1;
            List<Key> path = keyPath();
            skipWs();
            if (!at(']')) {
                throw error(pos, "expected ']'");
            }
            pos++;
            if (array) {
                if (!at(']')) {
                    throw error(pos, "expected ']]'");
                }
                pos++;
            }
            Table t = root;
            for (int i = 0; i < path.size() - 1; i++) {
                t = descendHeader(t, path.get(i));
            }
            Key last = path.get(path.size() - 1);
            Value existing = t.raw(last.name());
            if (array) {
                current = newArrayElement(t, last, existing);
            } else {
                current = defineTable(t, last, existing, path);
            }
        }

        private Table descendHeader(Table t, Key k) {
            Value v = t.raw(k.name());
            if (v == null) {
                Table n = new Table();
                kinds.put(n, Kind.IMPLICIT);
                t.put(k.name(), n);
                return n;
            }
            if (v instanceof Table tt) {
                Kind kind = kinds.get(tt);
                if (kind == Kind.INLINE) {
                    throw error(k.pos(), "cannot extend inline table '" + k.name() + "'");
                }
                if (kind == Kind.DOTTED) {
                    throw error(k.pos(), "cannot extend table '" + k.name() + "' defined by dotted keys");
                }
                return tt;
            }
            if (v instanceof Value.Arr a && tableArrays.contains(a)) {
                return lastElement(a);
            }
            throw error(k.pos(), "'" + k.name() + "' is not a table");
        }

        private Table lastElement(Value.Arr a) {
            return (Table) a.items().get(a.items().size() - 1);
        }

        private Table defineTable(Table parent, Key last, Value existing, List<Key> path) {
            if (existing == null) {
                Table n = new Table();
                kinds.put(n, Kind.EXPLICIT);
                parent.put(last.name(), n);
                return n;
            }
            if (existing instanceof Table tt) {
                Kind kind = kinds.get(tt);
                if (kind == Kind.IMPLICIT) {
                    kinds.put(tt, Kind.EXPLICIT);
                    return tt;
                }
                if (kind == Kind.INLINE) {
                    throw error(last.pos(), "cannot extend inline table '" + last.name() + "'");
                }
                if (kind == Kind.DOTTED) {
                    throw error(last.pos(), "table '" + last.name() + "' is already defined by dotted keys");
                }
            }
            throw error(last.pos(), "table '" + last.name() + "' is already defined");
        }

        private Table newArrayElement(Table parent, Key last, Value existing) {
            Value.Arr arr;
            if (existing == null) {
                arr = new Value.Arr();
                tableArrays.add(arr);
                parent.put(last.name(), arr);
            } else if (existing instanceof Value.Arr a && tableArrays.contains(a)) {
                arr = a;
            } else {
                throw error(last.pos(), "cannot redefine '" + last.name() + "' as an array of tables");
            }
            Table n = new Table();
            kinds.put(n, Kind.EXPLICIT);
            arr.add(n);
            return n;
        }

        // ---------- key/value ----------

        /** Parses {@code dotted.key = value} and stores it in {@code base}. */
        private void keyValue(Table base) {
            List<Key> path = keyPath();
            skipWs();
            if (!at('=')) {
                throw error(pos, "expected '='");
            }
            pos++;
            skipWs();
            Value v = value();
            Table t = base;
            for (int i = 0; i < path.size() - 1; i++) {
                Key k = path.get(i);
                Value ex = t.raw(k.name());
                if (ex == null) {
                    Table n = new Table();
                    kinds.put(n, Kind.DOTTED);
                    t.put(k.name(), n);
                    t = n;
                } else if (ex instanceof Table tt && kinds.get(tt) == Kind.DOTTED) {
                    t = tt;
                } else {
                    throw error(k.pos(), "key '" + k.name() + "' is already defined and cannot be extended");
                }
            }
            Key last = path.get(path.size() - 1);
            if (t.raw(last.name()) != null) {
                throw error(last.pos(), "duplicate key '" + last.name() + "'");
            }
            t.put(last.name(), v);
        }

        // ---------- values ----------

        private Value value() {
            if (eof()) {
                throw error(pos, "expected value");
            }
            return switch (peek()) {
                case '"' -> new Value.Str(basicString());
                case '\'' -> new Value.Str(literalString());
                case '[' -> array();
                case '{' -> inlineTable();
                default -> scalar();
            };
        }

        private String basicString() {
            int start = pos;
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof() || peek() == '\n' || peek() == '\r') {
                    throw error(start, "unterminated string");
                }
                char c = peek();
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c == '\\') {
                    escape(sb);
                    continue;
                }
                if (c < 0x20 && c != '\t') {
                    throw error(pos, "control character in string");
                }
                sb.append(c);
                pos++;
            }
        }

        private void escape(StringBuilder sb) {
            int esc = pos;
            pos++;
            if (eof()) {
                throw error(esc, "unterminated string");
            }
            char c = peek();
            pos++;
            switch (c) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case 'u' -> {
                    if (pos + 4 > s.length()) {
                        throw error(esc, "invalid unicode escape");
                    }
                    int cp = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        if (d < 0) {
                            throw error(esc, "invalid unicode escape");
                        }
                        cp = cp * 16 + d;
                    }
                    if (cp >= 0xD800 && cp <= 0xDFFF) {
                        throw error(esc, "surrogate code point in unicode escape");
                    }
                    pos += 4;
                    sb.append((char) cp);
                }
                default -> throw error(esc, "invalid escape '\\" + c + "'");
            }
        }

        private String literalString() {
            int start = pos;
            pos++;
            int from = pos;
            while (true) {
                if (eof() || peek() == '\n' || peek() == '\r') {
                    throw error(start, "unterminated string");
                }
                if (peek() == '\'') {
                    String r = s.substring(from, pos);
                    pos++;
                    return r;
                }
                pos++;
            }
        }

        private void enter(int at) {
            if (++depth > MAX_DEPTH) {
                throw error(at, "nesting too deep");
            }
        }

        private Value array() {
            int start = pos;
            enter(start);
            pos++;
            Value.Arr arr = new Value.Arr();
            while (true) {
                skipBlank();
                if (eof()) {
                    throw error(start, "unterminated array");
                }
                if (peek() == ']') {
                    pos++;
                    break;
                }
                arr.add(value());
                skipBlank();
                if (eof()) {
                    throw error(start, "unterminated array");
                }
                if (peek() == ',') {
                    pos++;
                } else if (peek() != ']') {
                    throw error(pos, "expected ',' or ']'");
                }
            }
            depth--;
            return arr;
        }

        private Value inlineTable() {
            int start = pos;
            enter(start);
            pos++;
            Table t = new Table();
            kinds.put(t, Kind.INLINE);
            skipWs();
            if (at('}')) {
                pos++;
                depth--;
                return t;
            }
            while (true) {
                skipWs();
                if (eof() || peek() == '\n' || peek() == '\r') {
                    throw error(start, "unterminated inline table");
                }
                keyValueInline(t);
                skipWs();
                if (eof() || peek() == '\n' || peek() == '\r') {
                    throw error(start, "unterminated inline table");
                }
                if (peek() == ',') {
                    pos++;
                    skipWs();
                    if (at('}')) {
                        throw error(pos, "trailing comma in inline table");
                    }
                } else if (peek() == '}') {
                    pos++;
                    depth--;
                    return t;
                } else {
                    throw error(pos, "expected ',' or '}'");
                }
            }
        }

        /** Like {@link #keyValue} but dotted keys may extend tables that earlier dotted keys of this inline table made. */
        private void keyValueInline(Table t) {
            keyValue(t);
        }

        private Value scalar() {
            int start = pos;
            while (!eof()) {
                char c = peek();
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == ']' || c == '}' || c == '#') {
                    break;
                }
                pos++;
            }
            if (pos == start) {
                throw error(start, "expected value");
            }
            String tok = s.substring(start, pos);
            if (tok.equals("true")) {
                return new Value.Bool(true);
            }
            if (tok.equals("false")) {
                return new Value.Bool(false);
            }
            try {
                if (INT.matcher(tok).matches()) {
                    return new Value.Int(Long.parseLong(tok.replace("_", "")));
                }
                if (HEX.matcher(tok).matches()) {
                    return new Value.Int(Long.parseLong(tok.substring(2).replace("_", ""), 16));
                }
            } catch (NumberFormatException e) {
                throw error(start, "integer out of range: " + tok);
            }
            if (FLOAT.matcher(tok).matches()) {
                double d = Double.parseDouble(tok.replace("_", ""));
                if (Double.isInfinite(d)) {
                    throw error(start, "float out of range: " + tok);
                }
                return new Value.Flt(d);
            }
            throw error(start, "invalid value '" + tok + "'");
        }
    }
}
