package ae.vfs;

/** Unchecked filesystem error carrying an {@link Errno}. */
public final class VfsException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Errno errno;

    public VfsException(Errno errno, String message) {
        super(errno + ": " + message);
        this.errno = errno;
    }

    public Errno errno() {
        return errno;
    }
}
