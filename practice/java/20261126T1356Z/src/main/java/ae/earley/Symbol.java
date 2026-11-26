package ae.earley;

/** A grammar symbol: a terminal (one code point) or a nonterminal (a name). */
public sealed interface Symbol {

    /** A terminal matching exactly one code point. */
    record Terminal(int codePoint) implements Symbol {
        @Override
        public String toString() {
            String s = new String(Character.toChars(codePoint));
            if (s.equals("'") || s.equals("\\")) {
                s = "\\" + s;
            }
            return "'" + s + "'";
        }
    }

    /** A reference to a nonterminal by name. */
    record NonTerminal(String name) implements Symbol {
        @Override
        public String toString() {
            return name;
        }
    }
}
