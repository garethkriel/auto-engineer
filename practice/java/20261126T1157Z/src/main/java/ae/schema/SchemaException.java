package ae.schema;

/** Thrown for a malformed schema or a {@code $ref} loop. Unchecked. */
public final class SchemaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SchemaException(String message) {
        super(message);
    }
}
