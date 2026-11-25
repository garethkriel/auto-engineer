package ae.proto;

/** Thrown when bytes do not form a valid message; {@link #getOffset()} is an absolute byte offset. */
public final class DecodeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int offset;

    public DecodeException(String message, int offset) {
        super(message + " at offset " + offset);
        this.offset = offset;
    }

    public int getOffset() {
        return offset;
    }
}
