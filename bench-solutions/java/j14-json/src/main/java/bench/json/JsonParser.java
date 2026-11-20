package bench.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Strict RFC 8259 JSON parser. */
public final class JsonParser {
    private final String s;
    private int pos;

    private JsonParser(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("null input");
        // Run on a thread with a large stack so very deep nesting cannot overflow.
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                result[0] = parseInternal(text);
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "json-parse", 512L * 1024 * 1024);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("interrupted", e);
        }
        if (failure[0] instanceof RuntimeException re) throw re;
        if (failure[0] instanceof StackOverflowError) throw new IllegalArgumentException("nesting too deep");
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
        return result[0];
    }

    private static Object parseInternal(String text) {
        JsonParser p = new JsonParser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != text.length()) throw p.err("trailing text");
        return v;
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException(m + " at " + pos);
    }

    private void ws() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private Object value() {
        if (pos >= s.length()) throw err("unexpected end");
        char c = s.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected character");
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, pos)) throw err("bad literal");
        pos += w.length();
    }

    private Object object() {
        pos++;
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        ws();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return m;
        }
        while (true) {
            ws();
            if (pos >= s.length() || s.charAt(pos) != '"') throw err("expected key");
            String k = string();
            ws();
            if (pos >= s.length() || s.charAt(pos) != ':') throw err("expected colon");
            pos++;
            ws();
            Object v = value();
            if (m.containsKey(k)) throw err("duplicate key");
            m.put(k, v);
            ws();
            if (pos >= s.length()) throw err("unterminated object");
            char c = s.charAt(pos++);
            if (c == '}') return m;
            if (c != ',') throw err("expected comma");
        }
    }

    private Object array() {
        pos++;
        ArrayList<Object> a = new ArrayList<>();
        ws();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (pos >= s.length()) throw err("unterminated array");
            char c = s.charAt(pos++);
            if (c == ']') return a;
            if (c != ',') throw err("expected comma");
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw err("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw err("control char");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) throw err("unterminated string");
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) throw err("bad unicode escape");
                    int cp = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        char ch = s.charAt(pos + i);
                        if (d < 0 || ch > 'f') throw err("bad unicode escape");
                        cp = cp * 16 + d;
                    }
                    pos += 4;
                    sb.append((char) cp);
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Object number() {
        int start = pos;
        if (s.charAt(pos) == '-') pos++;
        if (pos >= s.length()) throw err("bad number");
        char c = s.charAt(pos);
        if (c == '0') {
            pos++;
        } else if (c >= '1' && c <= '9') {
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
        } else {
            throw err("bad number");
        }
        boolean integral = true;
        if (pos < s.length() && s.charAt(pos) == '.') {
            integral = false;
            pos++;
            int d = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            if (pos == d) throw err("bad fraction");
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            integral = false;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            int d = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            if (pos == d) throw err("bad exponent");
        }
        String t = s.substring(start, pos);
        if (integral) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                // too large: fall through to Double
            }
        }
        return Double.parseDouble(t);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
