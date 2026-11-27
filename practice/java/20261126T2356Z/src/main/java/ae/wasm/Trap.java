package ae.wasm;

/** A run-time trap raised by the interpreter. */
public final class Trap extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Trap categories. */
    public enum Kind { UNREACHABLE, DIV_ZERO, INT_OVERFLOW, OUT_OF_BOUNDS, STACK_EXHAUSTED, OUT_OF_FUEL }

    private final Kind kind;

    Trap(Kind kind) {
        super(kind.name(), null, false, false);
        this.kind = kind;
    }

    /** The trap category. */
    public Kind kind() {
        return kind;
    }
}
