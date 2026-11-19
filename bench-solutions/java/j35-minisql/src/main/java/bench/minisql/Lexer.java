package bench.minisql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Converts SQL text into tokens. */
final class Lexer {
    static final Set<String> KEYWORDS = Set.of(
        "CREATE", "TABLE", "DROP", "INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE", "GROUP", "BY",
        "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "UPDATE", "SET", "DELETE", "AND", "OR", "NOT",
        "NULL", "IS", "AS", "TRUE", "FALSE", "INT", "TEXT", "COUNT", "SUM", "MIN", "MAX");

    private Lexer() { }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private static boolean letter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }

    static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                i++;
            } else if (digit(c)) {
                int j = i;
                while (j < n && digit(s.charAt(j))) {
                    j++;
                }
                long v;
                try {
                    v = Long.parseLong(s.substring(i, j));
                } catch (NumberFormatException e) {
                    throw new SqlException("integer literal out of range");
                }
                out.add(new Tok(Tok.Kind.NUM, s.substring(i, j), v));
                i = j;
            } else if (letter(c)) {
                int j = i;
                while (j < n && (letter(s.charAt(j)) || digit(s.charAt(j)))) {
                    j++;
                }
                String w = s.substring(i, j);
                String up = w.toUpperCase(java.util.Locale.ROOT);
                if (KEYWORDS.contains(up)) {
                    out.add(new Tok(Tok.Kind.KW, up, 0));
                } else {
                    out.add(new Tok(Tok.Kind.ID, w.toLowerCase(java.util.Locale.ROOT), 0));
                }
                i = j;
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\'') {
                        if (j + 1 < n && s.charAt(j + 1) == '\'') {
                            sb.append('\'');
                            j += 2;
                        } else {
                            closed = true;
                            j++;
                            break;
                        }
                    } else {
                        sb.append(d);
                        j++;
                    }
                }
                if (!closed) {
                    throw new SqlException("unterminated string literal");
                }
                out.add(new Tok(Tok.Kind.STR, sb.toString(), 0));
                i = j;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals("<>") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("||")) {
                    out.add(new Tok(Tok.Kind.SYM, two.equals("!=") ? "<>" : two, 0));
                    i += 2;
                } else if ("(),;*+-/%=<>".indexOf(c) >= 0) {
                    out.add(new Tok(Tok.Kind.SYM, String.valueOf(c), 0));
                    i++;
                } else {
                    throw new SqlException("unexpected character '" + c + "'");
                }
            }
        }
        out.add(new Tok(Tok.Kind.EOF, "", 0));
        return out;
    }
}
