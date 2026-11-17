package tmpl;

/** Base class of all template errors; carries a 1-based line and column. */
public abstract class TemplateException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;

    TemplateException(String message, int line, int column) {
        super(message + " (line " + line + ", column " + column + ")");
        this.line = line;
        this.column = column;
    }

    /** 1-based line of the offending token. */
    public int getLine() {
        return line;
    }

    /** 1-based column of the offending token. */
    public int getColumn() {
        return column;
    }
}
