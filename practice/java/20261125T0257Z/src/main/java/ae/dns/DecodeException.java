package ae.dns;

/** Thrown by {@link Wire#decode} for any malformed message; it is the only exception the decoder throws. */
public final class DecodeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DecodeException(String message) {
        super(message);
    }
}
