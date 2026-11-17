package tpl;

/** Thrown by {@link Template#render(java.util.Map)} when the data does not fit the template. */
public class TemplateRenderException extends TemplateException {
    private static final long serialVersionUID = 1L;

    TemplateRenderException(String message, int line, int column) {
        super(message, line, column);
    }
}
