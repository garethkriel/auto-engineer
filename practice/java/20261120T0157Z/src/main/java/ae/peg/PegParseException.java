package ae.peg;

import java.util.SortedSet;

/** Full-input parse failure, reported at the farthest position any terminal failed. */
public final class PegParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int position;
    private final int line;
    private final int column;
    private final transient SortedSet<String> expected;

    PegParseException(String message, int position, int line, int column, SortedSet<String> expected) {
        super(message);
        this.position = position;
        this.line = line;
        this.column = column;
        this.expected = expected;
    }

    public int position() {
        return position;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public SortedSet<String> expected() {
        return expected;
    }
}
