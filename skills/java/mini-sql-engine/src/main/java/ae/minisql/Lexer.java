package ae.minisql;

import java.util.ArrayList;
import java.util.List;

/** Splits SQL text into tokens. Whitespace and {@code --} line comments are skipped. */
final class Lexer {
    private Lexer() {}

    static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0, line = 1, lineStart = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int col = i - lineStart + 1;
            if (c == '\n') {
                line++;
                lineStart = ++i;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                out.add(new Token(Token.Kind.IDENT, s.substring(i, j), line, col));
                i = j;
            } else if (c >= '0' && c <= '9') {
                int j = i;
                while (j < s.length() && s.charAt(j) >= '0' && s.charAt(j) <= '9') j++;
                out.add(new Token(Token.Kind.NUMBER, s.substring(i, j), line, col));
                i = j;
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < s.length()) {
                    char d = s.charAt(j);
                    if (d == '\'') {
                        if (j + 1 < s.length() && s.charAt(j + 1) == '\'') {
                            sb.append('\'');
                            j += 2;
                            continue;
                        }
                        closed = true;
                        j++;
                        break;
                    }
                    if (d == '\n') {
                        line++;
                        lineStart = j + 1;
                    }
                    sb.append(d);
                    j++;
                }
                if (!closed) throw new SqlException("line " + line + ", col " + col + ": unterminated string literal");
                out.add(new Token(Token.Kind.STRING, sb.toString(), line, col));
                i = j;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (two.equals("<>") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("||")) {
                    out.add(new Token(Token.Kind.SYMBOL, two.equals("!=") ? "<>" : two, line, col));
                    i += 2;
                } else if ("(),.*+-/%=<>;".indexOf(c) >= 0) {
                    out.add(new Token(Token.Kind.SYMBOL, String.valueOf(c), line, col));
                    i++;
                } else {
                    throw new SqlException("line " + line + ", col " + col + ": unexpected character '" + c + "'");
                }
            }
        }
        out.add(new Token(Token.Kind.EOF, "", line, i - lineStart + 1));
        return out;
    }
}
