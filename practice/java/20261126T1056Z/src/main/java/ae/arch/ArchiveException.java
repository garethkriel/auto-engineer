package ae.arch;

/** A malformed, unsafe or unrepresentable archive or file tree. */
public final class ArchiveException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ArchiveException(String message) {
        super(message);
    }

    public ArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
