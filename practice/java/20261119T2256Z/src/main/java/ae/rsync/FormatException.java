package ae.rsync;

/** A serialised signature or delta is malformed, truncated or corrupted. */
public final class FormatException extends Exception {
    private static final long serialVersionUID = 1L;

    public FormatException(String message) {
        super(message);
    }
}
