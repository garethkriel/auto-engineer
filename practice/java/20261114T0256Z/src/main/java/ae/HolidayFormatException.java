package ae;

/** Checked exception for a malformed holiday line; carries the 1-based line number. */
public final class HolidayFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int lineNumber;

    public HolidayFormatException(int lineNumber, String text, Throwable cause) {
        super("line " + lineNumber + ": invalid ISO date '" + text + "'", cause);
        this.lineNumber = lineNumber;
    }

    public int lineNumber() {
        return lineNumber;
    }
}
