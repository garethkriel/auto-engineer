package bench.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, non-recursive RFC 8259 JSON parser. */
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

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException(m + " at " + pos);
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

    private char peek() {
        if (pos >= s.length()) {
            throw err("unexpected end");
        }
        return s.charAt(pos);
    }

    @SuppressWarnings("unchecked")
    private Object run() {
        List<Object> stack = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        outer:
        while (true) {
            skipWs();
            char c = peek();
            Object v;
            if (c == '{') {
                pos++;
                skipWs();
                if (peek() == '}') {
                    pos++;
                    v = new LinkedHashMap<String, Object>();
                } else {
                    stack.add(new LinkedHashMap<String, Object>());
                    keys.add(readKey());
                    continue outer;
                }
            } else if (c == '[') {
                pos++;
                skipWs();
                if (peek() == ']') {
                    pos++;
                    v = new ArrayList<Object>();
                } else {
                    stack.add(new ArrayList<Object>());
                    keys.add(null);
                    continue outer;
                }
            } else {
                v = scalar(c);
            }
            while (true) {
                if (stack.isEmpty()) {
                    skipWs();
                    if (pos != s.length()) {
                        throw err("trailing content");
                    }
                    return v;
                }
                int top = stack.size() - 1;
                Object container = stack.get(top);
                boolean isList = container instanceof List;
                if (isList) {
                    ((List<Object>) container).add(v);
                } else {
                    Map<String, Object> m = (Map<String, Object>) container;
                    String k = keys.get(top);
                    if (m.containsKey(k)) {
                        throw err("duplicate key " + k);
                    }
                    m.put(k, v);
                }
                skipWs();
                char d = peek();
                if (d == ',') {
                    pos++;
                    if (!isList) {
                        skipWs();
                        keys.set(top, readKey());
                    }
                    continue outer;
                } else if ((isList && d == ']') || (!isList && d == '}')) {
                    pos++;
                    v = container;
                    stack.remove(top);
                    keys.remove(top);
                } else {
                    throw err("unexpected character '" + d + "'");
                }
            }
        }
    }

    private String readKey() {
        if (peek() != '"') {
            throw err("expected string key");
        }
        String k = string();
        skipWs();
        if (peek() != ':') {
            throw err("expected ':'");
        }
        pos++;
        return k;
    }

    private Object scalar(char c) {
        if (c == '"') {
            return string();
        }
        if (c == 't') {
            literal("true");
            return Boolean.TRUE;
        }
        if (c == 'f') {
            literal("false");
            return Boolean.FALSE;
        }
        if (c == 'n') {
            literal("null");
            return null;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return number();
        }
        throw err("unexpected character '" + c + "'");
    }

    private void literal(String w) {
        if (!s.startsWith(w, pos)) {
            throw err("bad literal");
        }
        pos += w.length();
    }

    private boolean digit() {
        return pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9';
    }

    private Object number() {
        int start = pos;
        boolean simple = true;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        if (!digit()) {
            throw err("bad number");
        }
        if (s.charAt(pos) == '0') {
            pos++;
        } else {
            while (digit()) {
                pos++;
            }
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            simple = false;
            pos++;
            if (!digit()) {
                throw err("bad fraction");
            }
            while (digit()) {
                pos++;
            }
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            simple = false;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            if (!digit()) {
                throw err("bad exponent");
            }
            while (digit()) {
                pos++;
            }
        }
        String t = s.substring(start, pos);
        if (simple) {
            try {
                return Long.valueOf(Long.parseLong(t));
            } catch (NumberFormatException e) {
                // too big for long: fall through to Double
            }
        }
        return Double.valueOf(Double.parseDouble(t));
    }

    private String string() {
        pos++; // opening quote
        StringBuilder sb = null;
        int runStart = pos;
        while (true) {
            if (pos >= s.length()) {
                throw err("unterminated string");
            }
            char c = s.charAt(pos);
            if (c == '"') {
                String res = sb == null ? s.substring(runStart, pos) : sb.append(s, runStart, pos).toString();
                pos++;
                return res;
            }
            if (c < 0x20) {
                throw err("control character in string");
            }
            if (c == '\\') {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(s, runStart, pos);
                pos++;
                if (pos >= s.length()) {
                    throw err("unterminated string");
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
                            throw err("bad unicode escape");
                        }
                        int cp = 0;
                        for (int i = 0; i < 4; i++) {
                            int h = Character.digit(s.charAt(pos + i), 16);
                            char hc = s.charAt(pos + i);
                            if (h < 0 || hc > 'f') {
                                throw err("bad unicode escape");
                            }
                            cp = cp * 16 + h;
                        }
                        pos += 4;
                        sb.append((char) cp);
                    }
                    default -> throw err("unknown escape");
                }
                runStart = pos;
            } else {
                pos++;
            }
        }
    }
}
