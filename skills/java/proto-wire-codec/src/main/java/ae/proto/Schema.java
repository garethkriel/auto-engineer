package ae.proto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A set of message types parsed from a proto3-like text. */
public final class Schema {
    static final int MAX_FIELD_NUMBER = 536_870_911;

    private final Map<String, MessageType> messages;

    private Schema(Map<String, MessageType> messages) {
        this.messages = messages;
    }

    public List<String> messageNames() {
        return List.copyOf(messages.keySet());
    }

    public MessageType message(String name) {
        MessageType t = messages.get(name);
        if (t == null) throw new IllegalArgumentException("no message " + name);
        return t;
    }

    // ---- parsing ----

    private record Tok(String text, int line, boolean number) {}

    private record Pending(Field field, String typeName, int line, MessageType owner) {}

    public static Schema parse(String text) {
        List<Tok> toks = tokenize(text);
        int[] p = {0};
        Map<String, MessageType> messages = new LinkedHashMap<>();
        List<Pending> pending = new ArrayList<>();
        while (p[0] < toks.size()) {
            Tok kw = toks.get(p[0]++);
            if (!kw.text().equals("message")) throw new SchemaException(kw.line(), "expected 'message' but found '" + kw.text() + "'");
            Tok name = next(toks, p, "message name");
            if (!isIdent(name)) throw new SchemaException(name.line(), "expected message name but found '" + name.text() + "'");
            if (messages.containsKey(name.text())) throw new SchemaException(name.line(), "duplicate message " + name.text());
            MessageType type = new MessageType(name.text());
            messages.put(name.text(), type);
            expect(toks, p, "{");
            while (!peekIs(toks, p, "}")) {
                parseField(toks, p, type, pending);
            }
            p[0]++; // the closing brace
        }
        for (Pending pd : pending) {
            Optional<Kind> kind = Kind.byKeyword(pd.typeName());
            if (kind.isPresent()) continue;
            MessageType target = messages.get(pd.typeName());
            if (target == null) throw new SchemaException(pd.line(), "unknown type " + pd.typeName());
            pd.field().bind(target);
        }
        return new Schema(messages);
    }

    private static void parseField(List<Tok> toks, int[] p, MessageType owner, List<Pending> pending) {
        Tok first = next(toks, p, "field type");
        boolean repeated = false;
        Tok type = first;
        if (first.text().equals("repeated")) {
            repeated = true;
            type = next(toks, p, "field type");
        }
        if (!isIdent(type)) throw new SchemaException(type.line(), "expected field type but found '" + type.text() + "'");
        Tok name = next(toks, p, "field name");
        if (!isIdent(name)) throw new SchemaException(name.line(), "expected field name but found '" + name.text() + "'");
        expect(toks, p, "=");
        Tok num = next(toks, p, "field number");
        if (!num.number()) throw new SchemaException(num.line(), "expected field number but found '" + num.text() + "'");
        Tok semi = next(toks, p, "';'");
        if (!semi.text().equals(";")) throw new SchemaException(semi.line(), "expected ';' but found '" + semi.text() + "'");
        long n = num.text().length() > 10 ? Long.MAX_VALUE : Long.parseLong(num.text());
        if (n < 1 || n > MAX_FIELD_NUMBER) {
            throw new SchemaException(num.line(), "field number " + num.text() + " out of range 1.." + MAX_FIELD_NUMBER);
        }
        if (n >= 19000 && n <= 19999) throw new SchemaException(num.line(), "field number " + n + " is reserved");
        if (owner.field((int) n) != null) throw new SchemaException(num.line(), "duplicate field number " + n);
        if (owner.field(name.text()) != null) throw new SchemaException(name.line(), "duplicate field name " + name.text());
        Kind kind = Kind.byKeyword(type.text()).orElse(Kind.MESSAGE);
        Field f = new Field(name.text(), (int) n, kind, repeated);
        owner.add(f);
        pending.add(new Pending(f, type.text(), type.line(), owner));
    }

    private static Tok next(List<Tok> toks, int[] p, String what) {
        if (p[0] >= toks.size()) {
            int line = toks.isEmpty() ? 1 : toks.get(toks.size() - 1).line();
            throw new SchemaException(line, "unexpected end of input, expected " + what);
        }
        return toks.get(p[0]++);
    }

    private static void expect(List<Tok> toks, int[] p, String punct) {
        Tok t = next(toks, p, "'" + punct + "'");
        if (!t.text().equals(punct)) throw new SchemaException(t.line(), "expected '" + punct + "' but found '" + t.text() + "'");
    }

    private static boolean peekIs(List<Tok> toks, int[] p, String punct) {
        if (p[0] >= toks.size()) {
            int line = toks.isEmpty() ? 1 : toks.get(toks.size() - 1).line();
            throw new SchemaException(line, "unexpected end of input, expected '" + punct + "'");
        }
        return toks.get(p[0]).text().equals(punct);
    }

    private static boolean isIdent(Tok t) {
        char c = t.text().charAt(0);
        return Character.isLetter(c) || c == '_';
    }

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int line = 1;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (s.startsWith("//", i)) {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (c == '{' || c == '}' || c == '=' || c == ';') {
                out.add(new Tok(String.valueOf(c), line, false));
                i++;
            } else if (c >= '0' && c <= '9') {
                int j = i;
                while (j < s.length() && s.charAt(j) >= '0' && s.charAt(j) <= '9') j++;
                out.add(new Tok(s.substring(i, j), line, true));
                i = j;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                out.add(new Tok(s.substring(i, j), line, false));
                i = j;
            } else {
                throw new SchemaException(line, "unexpected character '" + c + "'");
            }
        }
        return out;
    }
}
