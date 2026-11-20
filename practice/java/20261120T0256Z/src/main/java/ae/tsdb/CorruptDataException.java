package ae.tsdb;

/** Thrown when encoded data (a bit stream, block or snapshot) is truncated, damaged or structurally invalid. */
public class CorruptDataException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @param message what is wrong */
    public CorruptDataException(String message) {
        super(message);
    }
}
