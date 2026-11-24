package ae.diff;

/** Thrown when a patch is malformed or does not apply to the given text. */
public class PatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PatchException(String message) {
        super(message);
    }
}
