package ae.logic;

/** A netlist text error, with the 1-based line it was found on. */
public final class NetlistException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int line;

    public NetlistException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    public int line() { return line; }
}
