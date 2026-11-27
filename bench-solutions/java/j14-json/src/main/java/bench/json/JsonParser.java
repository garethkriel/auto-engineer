package bench.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Strict RFC 8259 JSON parser returning plain Java objects. */
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
        Throwable[] err = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                JsonParser p = new JsonParser(text);
                p.skipWs();
                result[0] = p.value();
                p.skipWs();
                if (p.pos != text.length()) {
                    throw p.fail("trailing characters");
                }
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "json-parser", 512L * 1024 * 1024);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("interrupted");
        }
        if (err[0] != null) {
            if (err[0] instanceof IllegalArgumentException iae) {
                throw iae;
            }
            if (err[0] instanceof RuntimeException re) {
                throw new IllegalArgumentException(re.getMessage(), re);
            }
            if (err[0] instanceof Error er) {
                throw er;
            }
            throw new IllegalArgumentException(err[0]);
        }
        return result[0];
    }

    private IllegalArgumentException fail(String msg) {
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

    private Object value() {
        if (pos >= s.length()) {
            throw fail("unexpected end");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw fail("unexpected character");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, pos)) {
            throw fail("bad literal");
        }
        pos += word.length();
    }

    private Object object() {
        pos++; // {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw fail("expected key");
            }
            String key = string();
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw fail("expected ':'");
            }
            pos++;
            skipWs();
            Object v = value();
            if (map.containsKey(key)) {
                throw fail("duplicate key");
            }
            map.put(key, v);
            skipWs();
            if (pos >= s.length()) {
                throw fail("unterminated object");
            }
            char c = s.charAt(pos++);
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw fail("expected ',' or '}'");
            }
        }
    }

    private Object array() {
        pos++; // [
        ArrayList<Object> list = new ArrayList<>();
        skipWs();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value());
            skipWs();
            if (pos >= s.length()) {
                throw fail("unterminated array");
            }
            char c = s.charAt(pos++);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw fail("expected ',' or ']'");
            }
        }
    }

    private String string() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw fail("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw fail("control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw fail("unterminated escape");
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
                        throw fail("bad unicode escape");
                    }
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        char h = s.charAt(pos + i);
                        boolean ascii = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
                        if (!ascii || d < 0) {
                            throw fail("bad unicode escape");
                        }
                        code = code * 16 + d;
                    }
                    pos += 4;
                    sb.append((char) code);
                    break;
                default:
                    throw fail("unknown escape");
            }
        }
    }

    private Object number() {
        int start = pos;
        boolean integral = true;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        if (pos >= s.length()) {
            throw fail("bad number");
        }
        char c = s.charAt(pos);
        if (c == '0') {
            pos++;
        } else if (c >= '1' && c <= '9') {
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
        } else {
            throw fail("bad number");
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            integral = false;
            pos++;
            int d = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == d) {
                throw fail("bad fraction");
            }
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            integral = false;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            int d = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == d) {
                throw fail("bad exponent");
            }
        }
        String tok = s.substring(start, pos);
        if (integral) {
            try {
                return Long.valueOf(Long.parseLong(tok));
            } catch (NumberFormatException e) {
                return Double.valueOf(Double.parseDouble(tok));
            }
        }
        return Double.valueOf(Double.parseDouble(tok));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
