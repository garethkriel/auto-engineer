package tpl;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tpl.Node.Cond;
import tpl.Node.Filter;
import tpl.Node.Operand;

/**
 * A compiled text template. Instances are immutable and may be rendered many times, from several threads.
 *
 * <p>See {@code TASK.md} for the syntax. Output is HTML-escaped unless the {@code raw} filter is used.
 */
public final class Template {
    /** Marker for "no such variable"; distinct from a present {@code null}. */
    private static final Object MISSING = new Object();

    /** One loop binding; the chain is the lexical scope, with the render data as the root. */
    private record Scope(Scope parent, String name, Object value) { }

    private final List<Node> nodes;
    private final Source source;

    Template(List<Node> nodes, Source source) {
        this.nodes = nodes;
        this.source = source;
    }

    /** Compiles {@code text}; throws {@link TemplateSyntaxException} when it is malformed. */
    public static Template parse(String text) {
        Objects.requireNonNull(text, "text");
        return Parser.parse(text);
    }

    /** Renders with {@code data}; throws {@link TemplateRenderException} when data and template disagree. */
    public String render(Map<String, ?> data) {
        Objects.requireNonNull(data, "data");
        StringBuilder out = new StringBuilder();
        renderNodes(nodes, null, data, out);
        return out.toString();
    }

    // ---- rendering ----

    private void renderNodes(List<Node> list, Scope scope, Map<String, ?> data, StringBuilder out) {
        for (Node node : list) {
            switch (node) {
                case Node.Text t -> out.append(t.text());
                case Node.Output o -> out.append(output(o, scope, data));
                case Node.If i -> renderNodes(test(i.cond(), scope, data) ? i.then() : i.otherwise(), scope, data, out);
                case Node.For f -> loop(f, scope, data, out);
            }
        }
    }

    private void loop(Node.For f, Scope scope, Map<String, ?> data, StringBuilder out) {
        Object value = resolve(f.source(), scope, data);
        if (value == MISSING) {
            throw undefined(f.source());
        }
        if (!(value instanceof Iterable<?> items)) {
            throw source.render("'" + f.source().text() + "' is not iterable", f.source().offset());
        }
        int length = items instanceof Collection<?> c ? c.size() : -1;
        List<?> snapshot = length >= 0 ? null : toList(items);
        Iterable<?> source2 = snapshot != null ? snapshot : items;
        int total = snapshot != null ? snapshot.size() : length;
        int n = 0;
        for (Object item : source2) {
            Map<String, Object> loopInfo = Map.of(
                    "index", n + 1, "index0", n, "length", total,
                    "first", n == 0, "last", n == total - 1);
            Scope inner = new Scope(new Scope(scope, f.variable(), item), "loop", loopInfo);
            renderNodes(f.body(), inner, data, out);
            n++;
        }
    }

    private static List<Object> toList(Iterable<?> items) {
        List<Object> list = new java.util.ArrayList<>();
        items.forEach(list::add);
        return list;
    }

    private String output(Node.Output o, Scope scope, Map<String, ?> data) {
        Object value = operand(o.operand(), scope, data);
        boolean raw = false;
        for (Filter f : o.filters()) {
            if (f.name().equals("default")) {
                if (value == MISSING || value == null || "".equals(value)) {
                    value = f.argument();
                }
                continue;
            }
            if (value == MISSING) {
                throw undefined(o.operand());
            }
            switch (f.name()) {
                case "raw" -> raw = true;
                case "upper" -> value = text(value).toUpperCase(Locale.ROOT);
                case "lower" -> value = text(value).toLowerCase(Locale.ROOT);
                case "trim" -> value = text(value).strip();
                case "length" -> value = length(value, o.offset());
                default -> throw new IllegalStateException("unhandled filter " + f.name());
            }
        }
        if (value == MISSING) {
            throw undefined(o.operand());
        }
        String s = text(value);
        return raw ? s : escape(s);
    }

    private long length(Object value, int offset) {
        if (value instanceof CharSequence cs) {
            return cs.length();
        }
        if (value instanceof Collection<?> c) {
            return c.size();
        }
        if (value instanceof Map<?, ?> m) {
            return m.size();
        }
        throw source.render("'length' needs a string, collection or map", offset);
    }

    private TemplateRenderException undefined(Operand operand) {
        if (operand instanceof Operand.Path p) {
            return source.render("undefined variable '" + p.text() + "'", p.offset());
        }
        throw new IllegalStateException("literals are never missing");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String escape(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            String rep = switch (s.charAt(i)) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> null;
            };
            if (rep != null && sb == null) {
                sb = new StringBuilder(s.length() + 16).append(s, 0, i);
            }
            if (sb != null) {
                sb.append(rep != null ? rep : String.valueOf(s.charAt(i)));
            }
        }
        return sb == null ? s : sb.toString();
    }

    // ---- values and conditions ----

    private Object operand(Operand op, Scope scope, Map<String, ?> data) {
        return switch (op) {
            case Operand.Path p -> resolve(p, scope, data);
            case Operand.Str s -> s.value();
            case Operand.Int i -> i.value();
        };
    }

    private Object resolve(Operand.Path path, Scope scope, Map<String, ?> data) {
        String[] segs = path.segments();
        Object current = MISSING;
        for (Scope s = scope; s != null; s = s.parent()) {
            if (s.name().equals(segs[0])) {
                current = s.value();
                break;
            }
        }
        if (current == MISSING && data.containsKey(segs[0])) {
            current = data.get(segs[0]);
        }
        for (int i = 1; i < segs.length && current != MISSING; i++) {
            current = member(current, segs[i]);
        }
        return current;
    }

    private static Object member(Object holder, String segment) {
        if (holder instanceof Map<?, ?> m) {
            return m.containsKey(segment) ? m.get(segment) : MISSING;
        }
        if (holder instanceof List<?> l && !segment.isEmpty() && segment.chars().allMatch(Character::isDigit)) {
            try {
                int idx = Integer.parseInt(segment);
                return idx < l.size() ? l.get(idx) : MISSING;
            } catch (NumberFormatException e) {
                return MISSING;
            }
        }
        return MISSING;
    }

    private boolean test(Cond cond, Scope scope, Map<String, ?> data) {
        return switch (cond) {
            case Cond.Truthy t -> truthy(operand(t.operand(), scope, data));
            case Cond.Not n -> !test(n.inner(), scope, data);
            case Cond.And a -> test(a.left(), scope, data) && test(a.right(), scope, data);
            case Cond.Or o -> test(o.left(), scope, data) || test(o.right(), scope, data);
            case Cond.Cmp c -> sameValue(operand(c.left(), scope, data), operand(c.right(), scope, data)) == c.equal();
        };
    }

    static boolean truthy(Object v) {
        if (v == null || v == MISSING) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            BigDecimal big = toBig(n);
            return big == null || big.signum() != 0;
        }
        if (v instanceof CharSequence cs) {
            return cs.length() > 0;
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    static boolean sameValue(Object a, Object b) {
        Object x = a == MISSING ? null : a;
        Object y = b == MISSING ? null : b;
        if (x instanceof Number n1 && y instanceof Number n2) {
            if (isIntegral(n1) && isIntegral(n2)) {
                return n1.longValue() == n2.longValue();
            }
            BigDecimal b1 = toBig(n1);
            BigDecimal b2 = toBig(n2);
            return b1 != null && b2 != null ? b1.compareTo(b2) == 0 : Objects.equals(x, y);
        }
        return Objects.equals(x, y);
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
    }

    /** Exact decimal value, or null for NaN and infinities. */
    private static BigDecimal toBig(Number n) {
        if (n instanceof BigDecimal bd) {
            return bd;
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : new BigDecimal(Double.toString(d));
        }
        try {
            return new BigDecimal(n.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
