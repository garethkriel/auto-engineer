package bench.minisql;

/** A lexical token. KW text is upper case, ID text is lower case. */
record Tok(Kind kind, String text, long num) {
    enum Kind { NUM, STR, ID, KW, SYM, EOF }
}
