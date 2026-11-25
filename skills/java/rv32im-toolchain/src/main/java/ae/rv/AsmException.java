package ae.rv;

/** Assembler error. The message starts with {@code line N:}. */
public class AsmException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AsmException(int line, String message) {
        super("line " + line + ": " + message);
    }
}
