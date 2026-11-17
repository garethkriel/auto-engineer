package bench.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Strict RFC 8259 parser. Runs on a large-stack thread so deep nesting is safe. */
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
        Object[] result = new Object[1];
        Throwable[] error = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                JsonParser p = new JsonParser(text);
                p.ws();
                result[0] = p.value();
                p.ws();
                if (p.pos != text.length()) {
                    throw new IllegalArgumentException("trailing text");
                }
            } catch (Throwable e) {
                error[0] = e;
            }
        }, "json-parse", 256L * 1024 * 1024);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (error[0] instanceof StackOverflowError) {
            throw new IllegalArgumentException("nesting too deep");
        }
        if (error[0] instanceof RuntimeException re) {
            throw re;
        }
        if (error[0] != null) {
            throw new IllegalArgumentException(error[0]);
        }
        return result[0];
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException(m + " at " + pos);
    }

    private void ws() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object value() {
        if (pos >= s.length()) {
            throw err("unexpected end");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw err("unexpected character");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, pos)) {
            throw err("bad literal");
        }
        pos += word.length();
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
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw err("key expected");
            }
            String k = string();
            ws();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw err("colon expected");
            }
            pos++;
            ws();
            Object v = value();
            if (m.containsKey(k)) {
                throw err("duplicate key");
            }
            m.put(k, v);
            ws();
            if (pos >= s.length()) {
                throw err("unterminated object");
            }
            char c = s.charAt(pos++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("comma expected");
            }
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
            if (pos >= s.length()) {
                throw err("unterminated array");
            }
            char c = s.charAt(pos++);
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw err("comma expected");
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw err("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw err("control character");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw err("unterminated escape");
            }
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
                    if (pos + 4 > s.length()) {
                        throw err("bad unicode escape");
                    }
                    int cp = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        char h = s.charAt(pos + i);
                        if (d < 0 || h > 'f') {
                            throw err("bad unicode escape");
                        }
                        cp = cp * 16 + d;
                    }
                    pos += 4;
                    sb.append((char) cp);
                    break;
                default:
                    throw err("unknown escape");
            }
        }
    }

    private Object number() {
        int start = pos;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        if (pos >= s.length() || !digit(s.charAt(pos))) {
            throw err("digit expected");
        }
        if (s.charAt(pos) == '0') {
            pos++;
        } else {
            while (pos < s.length() && digit(s.charAt(pos))) {
                pos++;
            }
        }
        boolean integer = true;
        if (pos < s.length() && s.charAt(pos) == '.') {
            integer = false;
            pos++;
            int f = pos;
            while (pos < s.length() && digit(s.charAt(pos))) {
                pos++;
            }
            if (pos == f) {
                throw err("digits expected after point");
            }
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            integer = false;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            int f = pos;
            while (pos < s.length() && digit(s.charAt(pos))) {
                pos++;
            }
            if (pos == f) {
                throw err("exponent digits expected");
            }
        }
        String tok = s.substring(start, pos);
        if (integer) {
            try {
                return Long.parseLong(tok);
            } catch (NumberFormatException e) {
                return Double.parseDouble(tok);
            }
        }
        return Double.parseDouble(tok);
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }
}
