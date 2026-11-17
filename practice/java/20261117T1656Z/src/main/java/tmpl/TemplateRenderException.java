package tmpl;

/** Thrown by {@link Template#render(java.util.Map)} when a value has the wrong type for an operation. */
public final class TemplateRenderException extends TemplateException {
    private static final long serialVersionUID = 1L;

    TemplateRenderException(String message, int line, int column) {
        super(message, line, column);
    }
}
