package bench.json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict RFC 8259 JSON parser (iterative, so deep nesting is safe). */
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

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException(msg + " at " + pos);
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

    private void readKeyAndColon(Deque<String> keys) {
        if (peek() != '"') {
            throw err("expected key");
        }
        String k = readString();
        skipWs();
        if (peek() != ':') {
            throw err("expected ':'");
        }
        pos++;
        skipWs();
        keys.push(k);
    }

    @SuppressWarnings("unchecked")
    private Object run() {
        Deque<Object> stack = new ArrayDeque<>();
        Deque<String> keys = new ArrayDeque<>();
        skipWs();
        Object result;
        outer:
        while (true) {
            char c = peek();
            Object val;
            if (c == '{') {
                pos++;
                skipWs();
                if (peek() == '}') {
                    pos++;
                    val = new LinkedHashMap<String, Object>();
                } else {
                    stack.push(new LinkedHashMap<String, Object>());
                    readKeyAndColon(keys);
                    continue;
                }
            } else if (c == '[') {
                pos++;
                skipWs();
                if (peek() == ']') {
                    pos++;
                    val = new ArrayList<Object>();
                } else {
                    stack.push(new ArrayList<Object>());
                    continue;
                }
            } else {
                val = readScalar();
            }
            while (true) {
                if (stack.isEmpty()) {
                    result = val;
                    break outer;
                }
                Object top = stack.peek();
                if (top instanceof List) {
                    ((List<Object>) top).add(val);
                    skipWs();
                    char d = peek();
                    pos++;
                    if (d == ',') {
                        skipWs();
                        continue outer;
                    } else if (d == ']') {
                        stack.pop();
                        val = top;
                    } else {
                        pos--;
                        throw err("expected ',' or ']'");
                    }
                } else {
                    Map<String, Object> m = (Map<String, Object>) top;
                    String key = keys.pop();
                    if (m.containsKey(key)) {
                        throw err("duplicate key " + key);
                    }
                    m.put(key, val);
                    skipWs();
                    char d = peek();
                    pos++;
                    if (d == ',') {
                        skipWs();
                        readKeyAndColon(keys);
                        continue outer;
                    } else if (d == '}') {
                        stack.pop();
                        val = top;
                    } else {
                        pos--;
                        throw err("expected ',' or '}'");
                    }
                }
            }
        }
        skipWs();
        if (pos != s.length()) {
            throw err("trailing content");
        }
        return result;
    }

    private Object readScalar() {
        char c = peek();
        if (c == '"') {
            return readString();
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return readNumber();
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
        throw err("unexpected character '" + c + "'");
    }

    private boolean digitAt(int p) {
        return p < s.length() && s.charAt(p) >= '0' && s.charAt(p) <= '9';
    }

    private Object readNumber() {
        int start = pos;
        int p = pos;
        if (s.charAt(p) == '-') {
            p++;
        }
        if (!digitAt(p)) {
            throw err("bad number");
        }
        if (s.charAt(p) == '0') {
            p++;
        } else {
            while (digitAt(p)) {
                p++;
            }
        }
        boolean integral = true;
        if (p < s.length() && s.charAt(p) == '.') {
            p++;
            if (!digitAt(p)) {
                pos = p;
                throw err("bad fraction");
            }
            while (digitAt(p)) {
                p++;
            }
            integral = false;
        }
        if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
            p++;
            if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) {
                p++;
            }
            if (!digitAt(p)) {
                pos = p;
                throw err("bad exponent");
            }
            while (digitAt(p)) {
                p++;
            }
            integral = false;
        }
        pos = p;
        String tok = s.substring(start, p);
        if (integral) {
            try {
                return Long.parseLong(tok);
            } catch (NumberFormatException e) {
                return Double.parseDouble(tok);
            }
        }
        return Double.parseDouble(tok);
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder sb = null;
        int runStart = pos;
        while (true) {
            if (pos >= s.length()) {
                throw err("unterminated string");
            }
            char c = s.charAt(pos);
            if (c == '"') {
                String tail = s.substring(runStart, pos);
                pos++;
                return sb == null ? tail : sb.append(tail).toString();
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
                char e = s.charAt(pos);
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
                        if (pos + 4 >= s.length()) {
                            throw err("bad unicode escape");
                        }
                        int v = 0;
                        for (int i = 1; i <= 4; i++) {
                            int d = Character.digit(s.charAt(pos + i), 16);
                            char hc = s.charAt(pos + i);
                            if (d < 0 || hc > 'f') {
                                throw err("bad unicode escape");
                            }
                            v = v * 16 + d;
                        }
                        sb.append((char) v);
                        pos += 4;
                    }
                    default -> throw err("unknown escape");
                }
                pos++;
                runStart = pos;
            } else {
                pos++;
            }
        }
    }
}
