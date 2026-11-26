package ae.utf;

/** Thrown for malformed or unencodable input; {@link #offset()} is the input index of the defect (or -1). */
public final class CodecException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final long offset;

    public CodecException(String message, long offset) {
        super(message + (offset >= 0 ? " at offset " + offset : ""));
        this.offset = offset;
    }

    public long offset() {
        return offset;
    }
}
