package ae.deflate;

/** Raised for every malformed, truncated or oversized compressed stream. */
public final class InflateException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InflateException(String message) {
        super(message);
    }
}
