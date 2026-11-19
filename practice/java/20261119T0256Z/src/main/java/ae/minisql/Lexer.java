package ae.minisql;

import java.util.ArrayList;
import java.util.List;

/** Splits sql text into tokens; the list always ends with one EOF token. */
final class Lexer {
    enum Kind { IDENT, INT, STRING, SYM, EOF }

    record Token(Kind kind, String text, int pos, int end) {}

    private Lexer() {}

    static List<Token> lex(String sql) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Token(Kind.IDENT, sql.substring(start, i), start, i));
            } else if (c >= '0' && c <= '9') {
                int start = i;
                while (i < n && sql.charAt(i) >= '0' && sql.charAt(i) <= '9') {
                    i++;
                }
                out.add(new Token(Kind.INT, sql.substring(start, i), start, i));
            } else if (c == '\'') {
                int start = i;
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        sb.append(d);
                        i++;
                    }
                }
                if (!closed) {
                    throw new SqlException("unterminated string literal", start);
                }
                out.add(new Token(Kind.STRING, sb.toString(), start, i));
            } else {
                int start = i;
                String two = i + 1 < n ? sql.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("!=") || two.equals("<>") || two.equals("||")) {
                    i += 2;
                } else if ("()+,;.*-/%=<>".indexOf(c) >= 0) {
                    i++;
                } else {
                    throw new SqlException("unexpected character '" + c + "'", start);
                }
                out.add(new Token(Kind.SYM, sql.substring(start, i), start, i));
            }
        }
        out.add(new Token(Kind.EOF, "", n, n));
        return out;
    }
}
