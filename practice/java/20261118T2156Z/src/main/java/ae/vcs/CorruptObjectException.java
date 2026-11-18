package ae.vcs;

/** A stored object or ref does not match its id or format. */
public final class CorruptObjectException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CorruptObjectException(String message) {
        super(message);
    }
}
