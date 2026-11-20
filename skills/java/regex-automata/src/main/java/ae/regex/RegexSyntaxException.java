package ae.regex;

/** Thrown for a malformed pattern; {@link #index()} is the offset in the pattern where the problem was detected. */
public final class RegexSyntaxException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int index;

    public RegexSyntaxException(String message, int index) {
        super(message + " at index " + index);
        this.index = index;
    }

    /** Offset in the pattern of the offending character (or of the construct that was left open). */
    public int index() {
        return index;
    }
}
