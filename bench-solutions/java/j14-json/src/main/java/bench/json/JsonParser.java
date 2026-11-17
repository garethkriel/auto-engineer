package bench.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public final class JsonParser {
    private final String s;
    private int pos;

    private JsonParser(String s) { this.s = s; }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("null");
        Object[] result = new Object[1];
        Throwable[] err = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                JsonParser p = new JsonParser(text);
                p.ws();
                result[0] = p.value();
                p.ws();
                if (p.pos != text.length()) throw new IllegalArgumentException("trailing text");
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "json-parser", 512L * 1024 * 1024);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err[0] instanceof IllegalArgumentException iae) throw iae;
        if (err[0] instanceof StackOverflowError) throw new IllegalArgumentException("too deeply nested");
        if (err[0] != null) throw new IllegalArgumentException(err[0]);
        return result[0];
    }

    private IllegalArgumentException fail(String m) {
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
        if (pos >= s.length()) throw fail("unexpected end");
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
                throw fail("unexpected character");
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, pos)) throw fail("bad literal");
        pos += w.length();
    }

    private Object object() {
        pos++;
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        ws();
        if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
        while (true) {
            ws();
            if (pos >= s.length() || s.charAt(pos) != '"') throw fail("expected key");
            String k = string();
            ws();
            if (pos >= s.length() || s.charAt(pos) != ':') throw fail("expected :");
            pos++;
            ws();
            Object v = value();
            if (m.containsKey(k)) throw fail("duplicate key");
            m.put(k, v);
            ws();
            if (pos >= s.length()) throw fail("unterminated object");
            char c = s.charAt(pos++);
            if (c == '}') return m;
            if (c != ',') throw fail("expected , or }");
        }
    }

    private Object array() {
        pos++;
        ArrayList<Object> a = new ArrayList<>();
        ws();
        if (pos < s.length() && s.charAt(pos) == ']') { pos++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (pos >= s.length()) throw fail("unterminated array");
            char c = s.charAt(pos++);
            if (c == ']') return a;
            if (c != ',') throw fail("expected , or ]");
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw fail("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw fail("control character");
            if (c != '\\') { sb.append(c); continue; }
            if (pos >= s.length()) throw fail("unterminated string");
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
                case 'u': {
                    if (pos + 4 > s.length()) throw fail("bad unicode escape");
                    int v = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        char h = s.charAt(pos + i);
                        boolean ascii = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
                        if (d < 0 || !ascii) throw fail("bad unicode escape");
                        v = v * 16 + d;
                    }
                    pos += 4;
                    sb.append((char) v);
                    break;
                }
                default: throw fail("unknown escape");
            }
        }
    }

    private Object number() {
        int start = pos;
        if (s.charAt(pos) == '-') pos++;
        if (pos >= s.length()) throw fail("bad number");
        char c = s.charAt(pos);
        if (c == '0') {
            pos++;
        } else if (c >= '1' && c <= '9') {
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
        } else {
            throw fail("bad number");
        }
        boolean integral = true;
        if (pos < s.length() && s.charAt(pos) == '.') {
            integral = false;
            pos++;
            int f = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            if (pos == f) throw fail("bad fraction");
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            integral = false;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            int f = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            if (pos == f) throw fail("bad exponent");
        }
        String t = s.substring(start, pos);
        if (integral) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                return Double.parseDouble(t);
            }
        }
        return Double.parseDouble(t);
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
}
