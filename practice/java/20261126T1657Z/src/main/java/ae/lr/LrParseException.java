package ae.lr;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** A syntax error: the token at {@code position} cannot continue any valid input. */
public final class LrParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int position;
    private final String found;
    private final transient Set<String> expected;

    /**
     * Creates the exception.
     *
     * @param position index of the offending token ({@code tokens.size()} at end of input)
     * @param found the offending token, {@code $} at end of input
     * @param expected terminals acceptable at this point
     */
    public LrParseException(int position, String found, Set<String> expected) {
        super("syntax error at token " + position + " ('" + found + "'): expected one of "
            + new TreeSet<>(expected));
        this.position = position;
        this.found = found;
        this.expected = Collections.unmodifiableSet(new TreeSet<>(expected));
    }

    /** Index of the offending token. */
    public int position() {
        return position;
    }

    /** The offending token ({@code $} for end of input). */
    public String found() {
        return found;
    }

    /** Sorted set of acceptable terminals. */
    public Set<String> expected() {
        return expected;
    }
}
