package ae.classfile;

/** Thrown for any malformed class file, descriptor, string or bytecode. */
public final class ClassFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ClassFormatException(String message) {
        super(message);
    }
}
