package tpl;

/** Thrown by {@link Template#parse(String)} for malformed templates. */
public class TemplateSyntaxException extends TemplateException {
    private static final long serialVersionUID = 1L;

    TemplateSyntaxException(String message, int line, int column) {
        super(message, line, column);
    }
}
