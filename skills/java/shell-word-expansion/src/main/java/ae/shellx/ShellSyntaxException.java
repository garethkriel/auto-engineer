package ae.shellx;

/** Raised for malformed input lines; {@link #position()} is a 0-based index into the line. */
public final class ShellSyntaxException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int position;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     * @param position 0-based index in the input line
     */
    public ShellSyntaxException(String message, int position) {
        super(message + " at " + position);
        this.position = position;
    }

    /** Returns the 0-based index in the input line where the problem starts. */
    public int position() {
        return position;
    }
}
