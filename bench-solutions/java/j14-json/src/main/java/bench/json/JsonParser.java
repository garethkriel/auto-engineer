package bench.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public final class JsonParser {
    private final String s;
    private int pos;

    private JsonParser(String s) { this.s = s; }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("null input");
        JsonParser p = new JsonParser(text);
        Object v = p.run();
        if (p.pos != text.length()) throw p.err("trailing text");
        return v;
    }

    private Object run() {
        java.util.ArrayDeque<Object> stack = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<String> keys = new java.util.ArrayDeque<>();
        Object v;
        while (true) {
            // parse start of a value
            ws();
            if (pos >= s.length()) throw err("unexpected end");
            char c = s.charAt(pos);
            if (c == '[') {
                pos++;
                ws();
                if (pos < s.length() && s.charAt(pos) == ']') { pos++; v = new ArrayList<Object>(); }
                else { stack.push(new ArrayList<Object>()); continue; }
            } else if (c == '{') {
                pos++;
                ws();
                if (pos < s.length() && s.charAt(pos) == '}') { pos++; v = new LinkedHashMap<String, Object>(); }
                else { stack.push(new LinkedHashMap<String, Object>()); keys.push(key()); continue; }
            } else {
                v = scalar();
            }
            // value complete: attach to parents
            while (true) {
                if (stack.isEmpty()) { ws(); return v; }
                Object top = stack.peek();
                ws();
                if (pos >= s.length()) throw err("unterminated container");
                char d = s.charAt(pos++);
                if (top instanceof ArrayList) {
                    @SuppressWarnings("unchecked") ArrayList<Object> l = (ArrayList<Object>) top;
                    l.add(v);
                    if (d == ',') break;
                    if (d != ']') throw err("expected , or ]");
                } else {
                    @SuppressWarnings("unchecked") LinkedHashMap<String, Object> m = (LinkedHashMap<String, Object>) top;
                    String k = keys.peek();
                    if (m.containsKey(k)) throw err("duplicate key");
                    m.put(k, v);
                    if (d == ',') { keys.pop(); ws(); keys.push(key()); break; }
                    if (d != '}') throw err("expected , or }");
                    keys.pop();
                }
                v = stack.pop();
            }
        }
    }

    private String key() {
        if (pos >= s.length() || s.charAt(pos) != '"') throw err("expected key");
        String k = string();
        ws();
        if (pos >= s.length() || s.charAt(pos) != ':') throw err("expected colon");
        pos++;
        return k;
    }

    private Object scalar() {
        char c = s.charAt(pos);
        switch (c) {
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected char");
        }
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException(m + " at " + pos);
    }

    private void ws() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++; else break;
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, pos)) throw err("bad literal");
        pos += w.length();
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw err("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw err("control char");
            if (c != '\\') { sb.append(c); continue; }
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
                    int v = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        char h = s.charAt(pos + i);
                        if (d < 0 || h > 'f') throw err("bad unicode escape");
                        v = v * 16 + d;
                    }
                    pos += 4;
                    sb.append((char) v);
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
        if (c == '0') pos++;
        else if (c >= '1' && c <= '9') { while (digit()) pos++; }
        else throw err("bad number");
        boolean integral = true;
        if (pos < s.length() && s.charAt(pos) == '.') {
            integral = false;
            pos++;
            if (!digit()) throw err("bad fraction");
            while (digit()) pos++;
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            integral = false;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            if (!digit()) throw err("bad exponent");
            while (digit()) pos++;
        }
        String t = s.substring(start, pos);
        if (integral) {
            try { return Long.parseLong(t); } catch (NumberFormatException ex) { /* too big */ }
        }
        return Double.parseDouble(t);
    }

    private boolean digit() {
        return pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9';
    }
}
