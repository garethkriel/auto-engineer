package ae.proto;

/** Thrown for schema text errors; the message starts with {@code line N:}. */
public final class SchemaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SchemaException(int line, String message) {
        super("line " + line + ": " + message);
    }
}
