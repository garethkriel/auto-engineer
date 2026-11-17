package tmpl;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Evaluates the AST: pattern matching over the sealed {@link Node} and {@link Expr} hierarchies. */
final class Renderer {
    private final String src;

    Renderer(String src) {
        this.src = src;
    }

    // ---- filters (arity table is shared with the parser) ------------------------

    private static int arity(String name) {
        return switch (name) {
            case "upper", "lower", "trim", "length", "raw" -> 0;
            case "default", "join" -> 1;
            default -> -1;
        };
    }

    static void checkFilter(String src, String name, int argc, int offset) {
        int expected = arity(name);
        if (expected < 0) {
            throw Positions.syntax(src, offset, "unknown filter '" + name + "'");
        }
        if (expected != argc) {
            throw Positions.syntax(src, offset, "filter '" + name + "' takes " + expected + " argument"
                    + (expected == 1 ? "" : "s") + " but got " + argc);
        }
    }

    // ---- nodes ------------------------------------------------------------------

    void renderNodes(List<Node> nodes, Scope scope, StringBuilder out) {
        for (Node node : nodes) {
            switch (node) {
                case Node.Text t -> out.append(t.text());
                case Node.Output o -> out.append(renderOutput(o, scope));
                case Node.If i -> renderIf(i, scope, out);
                case Node.For f -> renderFor(f, scope, out);
            }
        }
    }

    private String renderOutput(Node.Output o, Scope scope) {
        Object value = eval(o.expr(), scope);
        boolean raw = false;
        for (Node.FilterCall f : o.filters()) {
            if (f.name().equals("raw")) {
                raw = true;
            }
            value = applyFilter(f, value);
        }
        String s = stringify(value);
        return raw ? s : escape(s);
    }

    private void renderIf(Node.If node, Scope scope, StringBuilder out) {
        for (Node.Branch b : node.branches()) {
            if (truthy(eval(b.condition(), scope))) {
                renderNodes(b.body(), scope, out);
                return;
            }
        }
        renderNodes(node.orElse(), scope, out);
    }

    private void renderFor(Node.For node, Scope scope, StringBuilder out) {
        Object source = eval(node.source(), scope);
        if (source == null) {
            return;
        }
        if (!(source instanceof Iterable<?> iterable)) {
            throw Positions.render(src, node.sourceOffset(),
                    "cannot iterate over " + typeName(source) + " (expected a list)");
        }
        List<Object> items = new java.util.ArrayList<>();
        iterable.forEach(items::add);
        int total = items.size();
        for (int i = 0; i < total; i++) {
            Map<String, Object> loop = Map.of("index", i + 1, "first", i == 0, "last", i == total - 1);
            Map<String, Object> bindings = new java.util.HashMap<>();
            bindings.put(node.variable(), items.get(i));
            bindings.put("loop", loop);
            renderNodes(node.body(), new Scope(bindings, scope), out);
        }
    }

    // ---- expressions ------------------------------------------------------------

    private Object eval(Expr e, Scope scope) {
        return switch (e) {
            case Expr.Lit l -> l.value();
            case Expr.Path p -> resolve(p, scope);
            case Expr.Not n -> !truthy(eval(n.operand(), scope));
            case Expr.And a -> truthy(eval(a.left(), scope)) && truthy(eval(a.right(), scope));
            case Expr.Or o -> truthy(eval(o.left(), scope)) || truthy(eval(o.right(), scope));
            case Expr.Cmp c -> compare(c, eval(c.left(), scope), eval(c.right(), scope));
        };
    }

    private static Object resolve(Expr.Path p, Scope scope) {
        Object cur = scope.lookup(p.parts().get(0));
        for (int i = 1; i < p.parts().size() && cur != null; i++) {
            String part = p.parts().get(i);
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(part);
            } else if (cur instanceof List<?> l) {
                cur = indexOrNull(l, part);
            } else {
                return null;
            }
        }
        return cur;
    }

    private static Object indexOrNull(List<?> list, String part) {
        for (int i = 0; i < part.length(); i++) {
            if (!Character.isDigit(part.charAt(i))) {
                return null;
            }
        }
        try {
            int index = Integer.parseInt(part);
            return index < list.size() ? list.get(index) : null;
        } catch (NumberFormatException tooBig) {
            return null;
        }
    }

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof BigDecimal d) {
            return d.signum() != 0;
        }
        if (v instanceof BigInteger i) {
            return i.signum() != 0;
        }
        if (v instanceof Double || v instanceof Float) {
            return ((Number) v).doubleValue() != 0.0;
        }
        if (v instanceof Number n) {
            return n.longValue() != 0;
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private Object compare(Expr.Cmp c, Object left, Object right) {
        String op = c.op();
        boolean equality = op.equals("==") || op.equals("!=");
        if (equality) {
            boolean eq;
            if (left instanceof Number a && right instanceof Number b) {
                eq = toBigDecimal(a, c.offset()).compareTo(toBigDecimal(b, c.offset())) == 0;
            } else {
                eq = Objects.equals(left, right);
            }
            return op.equals("==") == eq;
        }
        int cmp;
        if (left instanceof Number a && right instanceof Number b) {
            cmp = toBigDecimal(a, c.offset()).compareTo(toBigDecimal(b, c.offset()));
        } else if (left instanceof String a && right instanceof String b) {
            cmp = a.compareTo(b);
        } else {
            throw Positions.render(src, c.offset(),
                    "cannot compare " + typeName(left) + " with " + typeName(right) + " using '" + op + "'");
        }
        return switch (op) {
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            default -> cmp >= 0;
        };
    }

    private BigDecimal toBigDecimal(Number n, int offset) {
        if (n instanceof BigDecimal d) {
            return d;
        }
        if (n instanceof BigInteger i) {
            return new BigDecimal(i);
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw Positions.render(src, offset, "cannot compare a non-finite number");
            }
            return n instanceof Float ? new BigDecimal(n.toString()) : BigDecimal.valueOf(d);
        }
        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) {
            return BigDecimal.valueOf(n.longValue());
        }
        try {
            return new BigDecimal(n.toString());
        } catch (NumberFormatException e) {
            throw Positions.render(src, offset, "cannot compare number of type " + typeName(n));
        }
    }

    // ---- values -----------------------------------------------------------------

    private static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Map) {
            return "map";
        }
        if (v instanceof Iterable) {
            return "list";
        }
        return v.getClass().getSimpleName();
    }

    static String stringify(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
            return v.toString();
        }
        if (v instanceof BigDecimal d) {
            return d.toPlainString();
        }
        return v.toString();
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

    private Object applyFilter(Node.FilterCall f, Object value) {
        return switch (f.name()) {
            case "raw" -> value;
            case "upper" -> stringify(value).toUpperCase(Locale.ROOT);
            case "lower" -> stringify(value).toLowerCase(Locale.ROOT);
            case "trim" -> stringify(value).strip();
            case "default" -> value == null || "".equals(value) ? f.args().get(0) : value;
            case "length" -> {
                if (value == null) {
                    yield 0;
                }
                if (value instanceof String s) {
                    yield s.codePointCount(0, s.length());
                }
                if (value instanceof Collection<?> c) {
                    yield c.size();
                }
                if (value instanceof Map<?, ?> m) {
                    yield m.size();
                }
                throw Positions.render(src, f.offset(), "cannot take length of " + typeName(value));
            }
            case "join" -> {
                if (value == null) {
                    yield "";
                }
                if (!(value instanceof Iterable<?> it)) {
                    throw Positions.render(src, f.offset(), "cannot join " + typeName(value) + " (expected a list)");
                }
                String sep = stringify(f.args().get(0));
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Object o : it) {
                    if (!first) {
                        sb.append(sep);
                    }
                    first = false;
                    sb.append(stringify(o));
                }
                yield sb.toString();
            }
            default -> throw new IllegalStateException("unchecked filter " + f.name());
        };
    }
}
