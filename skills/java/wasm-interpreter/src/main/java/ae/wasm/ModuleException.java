package ae.wasm;

/** A module that is malformed (binary format), invalid (validation) or cannot be linked (instantiation). */
public final class ModuleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Failure category. */
    public enum Kind {
        /** The binary violates the encoding rules. */
        MALFORMED,
        /** The module is well-formed but fails validation or uses an unsupported feature. */
        INVALID,
        /** Instantiation failed (memory too large, data segment outside memory). */
        LINK
    }

    private final Kind kind;

    ModuleException(Kind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    /** The failure category. */
    public Kind kind() {
        return kind;
    }
}
