package bench.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, non-recursive RFC 8259 JSON parser (handles arbitrary nesting depth). */
public final class JsonParser {
    private final String s;
    private int pos;

    private JsonParser(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("null input");
        }
        return new JsonParser(text).run();
    }

    private static IllegalArgumentException err(String m, int at) {
        return new IllegalArgumentException(m + " at " + at);
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object run() {
        List<Object> stack = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        while (true) {
            skipWs();
            if (pos >= s.length()) {
                throw err("unexpected end", pos);
            }
            char c = s.charAt(pos);
            Object value;
            if (c == '{') {
                pos++;
                skipWs();
                Map<String, Object> m = new LinkedHashMap<>();
                if (pos < s.length() && s.charAt(pos) == '}') {
                    pos++;
                    value = m;
                } else {
                    stack.add(m);
                    keys.add(readKey());
                    continue;
                }
            } else if (c == '[') {
                pos++;
                skipWs();
                List<Object> l = new ArrayList<>();
                if (pos < s.length() && s.charAt(pos) == ']') {
                    pos++;
                    value = l;
                } else {
                    stack.add(l);
                    keys.add(null);
                    continue;
                }
            } else {
                value = scalar();
            }
            while (true) {
                if (stack.isEmpty()) {
                    skipWs();
                    if (pos != s.length()) {
                        throw err("trailing content", pos);
                    }
                    return value;
                }
                int top = stack.size() - 1;
                Object container = stack.get(top);
                if (container instanceof List) {
                    ((List<Object>) container).add(value);
                } else {
                    Map<String, Object> m = (Map<String, Object>) container;
                    String k = keys.get(top);
                    if (m.containsKey(k)) {
                        throw err("duplicate key '" + k + "'", pos);
                    }
                    m.put(k, value);
                }
                skipWs();
                if (pos >= s.length()) {
                    throw err("unexpected end", pos);
                }
                char d = s.charAt(pos++);
                if (d == ',') {
                    if (container instanceof java.util.Map) {
                        keys.set(top, readKey());
                    }
                    break;
                } else if ((d == ']' && container instanceof List) || (d == '}' && container instanceof Map)) {
                    stack.remove(top);
                    keys.remove(top);
                    value = container;
                } else {
                    throw err("unexpected character '" + d + "'", pos - 1);
                }
            }
        }
    }

    private String readKey() {
        skipWs();
        if (pos >= s.length() || s.charAt(pos) != '"') {
            throw err("expected string key", pos);
        }
        pos++;
        String k = string();
        skipWs();
        if (pos >= s.length() || s.charAt(pos) != ':') {
            throw err("expected ':'", pos);
        }
        pos++;
        return k;
    }

    private Object scalar() {
        char c = s.charAt(pos);
        if (c == '"') {
            pos++;
            return string();
        }
        if (s.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return number();
        }
        throw err("unexpected character '" + c + "'", pos);
    }

    private boolean digitAt(int i) {
        return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
    }

    private Object number() {
        int start = pos;
        int i = pos;
        if (s.charAt(i) == '-') {
            i++;
        }
        if (!digitAt(i)) {
            throw err("bad number", start);
        }
        if (s.charAt(i) == '0') {
            i++;
        } else {
            while (digitAt(i)) {
                i++;
            }
        }
        boolean integral = true;
        if (i < s.length() && s.charAt(i) == '.') {
            integral = false;
            i++;
            if (!digitAt(i)) {
                throw err("bad fraction", i);
            }
            while (digitAt(i)) {
                i++;
            }
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            integral = false;
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            if (!digitAt(i)) {
                throw err("bad exponent", i);
            }
            while (digitAt(i)) {
                i++;
            }
        }
        String t = s.substring(start, i);
        pos = i;
        if (integral) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                return Double.parseDouble(t);
            }
        }
        return Double.parseDouble(t);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** Called after the opening quote. */
    private String string() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw err("unterminated string", pos);
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw err("control character in string", pos - 1);
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw err("unterminated string", pos);
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > s.length()) {
                        throw err("bad unicode escape", pos);
                    }
                    int v = 0;
                    for (int k = 0; k < 4; k++) {
                        int h = hex(s.charAt(pos + k));
                        if (h < 0) {
                            throw err("bad unicode escape", pos);
                        }
                        v = v * 16 + h;
                    }
                    pos += 4;
                    sb.append((char) v);
                }
                default -> throw err("unknown escape", pos - 1);
            }
        }
    }
}
