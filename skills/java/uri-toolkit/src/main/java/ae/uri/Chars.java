package ae.uri;

/** RFC 3986 character classes (ASCII only). */
public final class Chars {
    private Chars() {}

    public static boolean isAlpha(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    public static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    public static boolean isHex(int c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    public static boolean isUnreserved(int c) {
        return isAlpha(c) || isDigit(c) || c == '-' || c == '.' || c == '_' || c == '~';
    }

    public static boolean isSubDelim(int c) {
        return c < 128 && "!$&'()*+,;=".indexOf(c) >= 0;
    }

    /** pchar without the percent-encoded alternative. */
    public static boolean isPcharLiteral(int c) {
        return isUnreserved(c) || isSubDelim(c) || c == ':' || c == '@';
    }

    static int hexValue(int c) {
        if (isDigit(c)) return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}
