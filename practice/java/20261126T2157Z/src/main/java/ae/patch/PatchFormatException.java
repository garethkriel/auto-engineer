package ae.patch;

/** Malformed unified-diff text; the message names the offending 1-based line. */
public final class PatchFormatException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public PatchFormatException(String message) {
        super(message);
    }
}
