package ae.mdlite;

import java.util.List;

/** Block-level AST nodes. */
public sealed interface Block {
    /** ATX heading, level 1..6. */
    record Heading(int level, List<Inline> inlines) implements Block { }

    /** Paragraph; soft line breaks are kept as "\n" inside {@link Inline.Text}. */
    record Paragraph(List<Inline> inlines) implements Block { }

    /** Fenced code block; {@code lang} is empty when absent. */
    record CodeBlock(String lang, String text) implements Block { }

    /** Block quote containing nested blocks. */
    record Quote(List<Block> blocks) implements Block { }

    /** Ordered or bullet list; each item is a sequence of inlines. */
    record ListBlock(boolean ordered, List<List<Inline>> items) implements Block { }

    /** Thematic break. */
    record Rule() implements Block { }
}
