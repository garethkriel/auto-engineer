package ae.xsort;

/** A run file is truncated or contains bytes that cannot be a valid record stream. */
public class CorruptRunException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CorruptRunException(String message, Throwable cause) {
        super(message, cause);
    }
}
