package ae.regex;

/** Regex abstract syntax tree. {@code +} and {@code ?} are desugared by the parser into {@link Repeat}. */
public sealed interface Node permits Node.Empty, Node.Lit, Node.Cat, Node.Alt, Node.Star, Node.Repeat {
    /** Matches only the empty string. */
    record Empty() implements Node { }

    /** Matches one char from the set. */
    record Lit(CharSet set) implements Node { }

    record Cat(Node left, Node right) implements Node { }

    record Alt(Node left, Node right) implements Node { }

    record Star(Node body) implements Node { }

    /** {@code body{min,max}}; {@code max == -1} means unbounded. */
    record Repeat(Node body, int min, int max) implements Node { }
}
