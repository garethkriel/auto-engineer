package tmpl;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed, immutable and thread-safe template. See {@code TASK.md} for the syntax.
 *
 * <pre>{@code
 * Template t = Template.parse("Hello {{ name | upper }}!");
 * t.render(Map.of("name", "ada")); // "Hello ADA!"
 * }</pre>
 */
public final class Template {
    private final String source;
    private final List<Node> nodes;

    private Template(String source, List<Node> nodes) {
        this.source = source;
        this.nodes = nodes;
    }

    /**
     * Parses {@code source}.
     *
     * @throws TemplateSyntaxException with line and column of the offending token
     */
    public static Template parse(String source) {
        Objects.requireNonNull(source, "source");
        return new Template(source, List.copyOf(Parser.parse(source)));
    }

    /**
     * Renders with the given context. The map is only read, never modified.
     *
     * @throws TemplateRenderException if a value has the wrong type for an operation
     */
    public String render(Map<String, ?> context) {
        Objects.requireNonNull(context, "context");
        StringBuilder out = new StringBuilder();
        new Renderer(source).renderNodes(nodes, new Scope(context, null), out);
        return out.toString();
    }
}
