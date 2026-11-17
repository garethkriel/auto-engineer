package tpl;

/** Base class of all template errors. Positions are 1-based. */
public class TemplateException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;

    protected TemplateException(String message, int line, int column) {
        super(message + " (line " + line + ", column " + column + ")");
        this.line = line;
        this.column = column;
    }

    /** 1-based line of the error. */
    public int line() {
        return line;
    }

    /** 1-based column of the error. */
    public int column() {
        return column;
    }
}
