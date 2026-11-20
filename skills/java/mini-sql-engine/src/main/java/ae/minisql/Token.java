package ae.minisql;

/** A lexical token with its 1-based position. */
record Token(Kind kind, String text, int line, int col) {
    enum Kind { IDENT, NUMBER, STRING, SYMBOL, EOF }

    String upper() {
        return text.toUpperCase(java.util.Locale.ROOT);
    }

    String where() {
        return "line " + line + ", col " + col;
    }
}
