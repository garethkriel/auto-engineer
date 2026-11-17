package tmpl;

/** Converts absolute source offsets to 1-based line/column pairs. */
final class Positions {
    private Positions() {
    }

    static int line(String src, int offset) {
        int line = 1;
        int end = Math.min(offset, src.length());
        for (int i = 0; i < end; i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static int column(String src, int offset) {
        int end = Math.min(offset, src.length());
        int lastNewline = src.lastIndexOf('\n', end - 1);
        return end - lastNewline;
    }

    static TemplateSyntaxException syntax(String src, int offset, String message) {
        return new TemplateSyntaxException(message, line(src, offset), column(src, offset));
    }

    static TemplateRenderException render(String src, int offset, String message) {
        return new TemplateRenderException(message, line(src, offset), column(src, offset));
    }
}
