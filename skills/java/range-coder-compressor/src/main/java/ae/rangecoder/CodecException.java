package ae.rangecoder;

/** Thrown when coded data is malformed, truncated or inconsistent. */
public final class CodecException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @param message what is wrong with the data */
    public CodecException(String message) {
        super(message);
    }
}
