package tmpl;

import java.util.ArrayList;
import java.util.List;

/** Splits a template into text, output ({@code {{ }}}) and tag ({@code {% %}}) tokens; drops comments. */
final class Lexer {
    enum Kind { TEXT, OUTPUT, TAG }

    /**
     * @param text          raw text, or the raw content between the delimiters (trim markers removed)
     * @param openOffset    offset of the first character of the token (the opening delimiter for OUTPUT/TAG)
     * @param contentOffset offset of {@code text}'s first character in the source
     */
    record Token(Kind kind, String text, int openOffset, int contentOffset) {
    }

    private Lexer() {
    }

    static List<Token> lex(String src) {
        List<Token> out = new ArrayList<>();
        int n = src.length();
        int pos = 0;
        boolean trimNext = false;
        while (pos < n) {
            int open = nextOpener(src, pos);
            if (open < 0) {
                addText(out, src, pos, n, trimNext, false);
                break;
            }
            char kindChar = src.charAt(open + 1);
            String close = kindChar == '{' ? "}}" : kindChar == '%' ? "%}" : "#}";
            int end = src.indexOf(close, open + 2);
            if (end < 0) {
                throw Positions.syntax(src, open, "unterminated '" + src.substring(open, open + 2) + "'");
            }
            int contentStart = open + 2;
            int contentEnd = end;
            boolean trimBefore = contentStart < contentEnd && src.charAt(contentStart) == '-';
            if (trimBefore) {
                contentStart++;
            }
            boolean trimAfter = contentEnd > contentStart && src.charAt(contentEnd - 1) == '-';
            if (trimAfter) {
                contentEnd--;
            }
            addText(out, src, pos, open, trimNext, trimBefore);
            if (kindChar != '#') {
                Kind kind = kindChar == '{' ? Kind.OUTPUT : Kind.TAG;
                out.add(new Token(kind, src.substring(contentStart, contentEnd), open, contentStart));
            }
            trimNext = trimAfter;
            pos = end + 2;
        }
        return out;
    }

    private static int nextOpener(String src, int from) {
        int i = src.indexOf('{', from);
        while (i >= 0 && i + 1 < src.length()) {
            char c = src.charAt(i + 1);
            if (c == '{' || c == '%' || c == '#') {
                return i;
            }
            i = src.indexOf('{', i + 1);
        }
        return -1;
    }

    private static void addText(List<Token> out, String src, int from, int to, boolean stripLeading,
            boolean stripTrailing) {
        String text = src.substring(from, to);
        if (stripLeading) {
            text = text.stripLeading();
        }
        if (stripTrailing) {
            text = text.stripTrailing();
        }
        if (!text.isEmpty()) {
            // after stripLeading the text starts later than 'from'; only openOffset matters for TEXT
            out.add(new Token(Kind.TEXT, text, from, from));
        }
    }
}
