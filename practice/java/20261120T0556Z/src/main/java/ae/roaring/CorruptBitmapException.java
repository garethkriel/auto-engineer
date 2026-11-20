package ae.roaring;

/** Thrown when a serialised bitmap image fails validation. */
public final class CorruptBitmapException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CorruptBitmapException(String message) {
        super(message);
    }
}
