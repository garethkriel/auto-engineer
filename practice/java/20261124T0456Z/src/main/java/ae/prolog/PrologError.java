package ae.prolog;

/** Every failure the parser or engine reports. */
public final class PrologError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Kind { SYNTAX, INSTANTIATION, TYPE, EXISTENCE, EVALUATION, RESOURCE, PERMISSION }

    private final Kind kind;

    public PrologError(Kind kind, String message) {
        super(kind.name().toLowerCase(java.util.Locale.ROOT) + ": " + message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
