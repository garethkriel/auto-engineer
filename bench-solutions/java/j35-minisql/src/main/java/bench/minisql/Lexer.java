package bench.minisql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Splits SQL text into tokens. */
final class Lexer {
    enum Kind { IDENT, KW, INT, STR, SYM, EOF }

    record Tok(Kind kind, String text, long num) { }

    static final Set<String> RESERVED = Set.of("CREATE", "TABLE", "DROP", "INSERT", "INTO", "VALUES", "SELECT",
            "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "UPDATE", "SET",
            "DELETE", "AND", "OR", "NOT", "NULL", "IS", "AS", "TRUE", "FALSE", "INT", "TEXT", "COUNT", "SUM", "MIN",
            "MAX");

    private Lexer() { }

    static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else if (isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < n && (isLetter(s.charAt(j)) || isDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                String w = s.substring(i, j);
                String up = w.toUpperCase(java.util.Locale.ROOT);
                if (RESERVED.contains(up)) {
                    out.add(new Tok(Kind.KW, up, 0));
                } else {
                    out.add(new Tok(Kind.IDENT, w.toLowerCase(java.util.Locale.ROOT), 0));
                }
                i = j;
            } else if (isDigit(c)) {
                int j = i + 1;
                while (j < n && isDigit(s.charAt(j))) {
                    j++;
                }
                try {
                    out.add(new Tok(Kind.INT, s.substring(i, j), Long.parseLong(s.substring(i, j))));
                } catch (NumberFormatException e) {
                    throw new SqlException("integer literal out of range");
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
                            continue;
                        }
                        closed = true;
                        j++;
                        break;
                    }
                    sb.append(d);
                    j++;
                }
                if (!closed) {
                    throw new SqlException("unterminated string literal");
                }
                out.add(new Tok(Kind.STR, sb.toString(), 0));
                i = j;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals("<>") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                        || two.equals("||")) {
                    out.add(new Tok(Kind.SYM, two.equals("!=") ? "<>" : two, 0));
                    i += 2;
                } else if ("(),;*+-/%=<>".indexOf(c) >= 0) {
                    out.add(new Tok(Kind.SYM, String.valueOf(c), 0));
                    i++;
                } else {
                    throw new SqlException("unexpected character: " + c);
                }
            }
        }
        out.add(new Tok(Kind.EOF, "", 0));
        return out;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
