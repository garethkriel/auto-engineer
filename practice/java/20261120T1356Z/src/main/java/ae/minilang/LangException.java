package ae.minilang;

/** Every failure of the toolchain, static or at run time, carries a {@link Kind}. */
public final class LangException extends RuntimeException {
    /** Error categories; engines must agree on these. */
    public enum Kind { SYNTAX, NAME, ARITY, DIV_ZERO, OVERFLOW, STACK_OVERFLOW, STEP_LIMIT, VERIFY }

    private final Kind kind;

    public LangException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
