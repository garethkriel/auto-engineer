package ae.minilang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Turns source text into tokens. Errors are SYNTAX with a position. */
final class Lexer {
    enum Type { NUM, ID, SYM, EOF }

    /** A token; for NUM the text is the decimal digits. */
    record Token(Type type, String text, int line, int col) {
        boolean is(String sym) {
            return type == Type.SYM && text.equals(sym);
        }

        boolean isKeyword(String kw) {
            return type == Type.ID && text.equals(kw);
        }
    }

    static final Set<String> KEYWORDS = Set.of("let", "fn", "if", "else", "while", "return", "print");
    private static final Set<String> TWO = Set.of("==", "!=", "<=", ">=", "&&", "||");
    private static final String ONE = "+-*/%<>=!(){},;";

    private Lexer() { }

    static List<Token> lex(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int line = 1;
        int col = 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                i++;
                line++;
                col = 1;
            } else if (c == ' ' || c == '\t' || c == '\r') {
                i++;
                col++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                    col++;
                }
            } else if (c >= '0' && c <= '9') {
                int start = i;
                while (i < n && src.charAt(i) >= '0' && src.charAt(i) <= '9') {
                    i++;
                }
                if (i < n && isIdentPart(src.charAt(i))) {
                    throw error(line, col + (i - start), "identifier cannot start with a digit");
                }
                out.add(new Token(Type.NUM, src.substring(start, i), line, col));
                col += i - start;
            } else if (isIdentStart(c)) {
                int start = i;
                while (i < n && isIdentPart(src.charAt(i))) {
                    i++;
                }
                out.add(new Token(Type.ID, src.substring(start, i), line, col));
                col += i - start;
            } else if (i + 1 < n && TWO.contains(src.substring(i, i + 2))) {
                out.add(new Token(Type.SYM, src.substring(i, i + 2), line, col));
                i += 2;
                col += 2;
            } else if (ONE.indexOf(c) >= 0) {
                out.add(new Token(Type.SYM, String.valueOf(c), line, col));
                i++;
                col++;
            } else {
                throw error(line, col, "unexpected character '" + c + "'");
            }
        }
        out.add(new Token(Type.EOF, "<eof>", line, col));
        return out;
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    static LangException error(int line, int col, String msg) {
        return new LangException(LangException.Kind.SYNTAX, "line " + line + ", col " + col + ": " + msg);
    }
}
