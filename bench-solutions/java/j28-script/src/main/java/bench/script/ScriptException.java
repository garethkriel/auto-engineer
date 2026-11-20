package bench.script;

/** Error raised for any lexical, syntax or runtime error in a script. */
public final class ScriptException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int line;
    private final int column;

    public ScriptException(int line, int column, String message) {
        super(line + ":" + column + ": " + message);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
