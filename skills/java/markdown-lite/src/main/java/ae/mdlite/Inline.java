package ae.mdlite;

import java.util.List;

/** Inline-level AST nodes. */
public sealed interface Inline {
    record Text(String value) implements Inline { }

    record Emph(List<Inline> children) implements Inline { }

    record Strong(List<Inline> children) implements Inline { }

    record Code(String value) implements Inline { }

    record Link(List<Inline> text, String url) implements Inline { }
}
