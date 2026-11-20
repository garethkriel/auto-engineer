package ae.peg;

import java.util.List;
import java.util.stream.Collectors;

/** Parsing expression. Records give structural equality, which the round-trip tests rely on. */
public sealed interface Expr {
    record Lit(String text) implements Expr { }
    record Any() implements Expr { }
    record Range(char lo, char hi) { }
    record CharClass(boolean negated, List<Range> ranges) implements Expr {
        public CharClass {
            ranges = List.copyOf(ranges);
        }
        boolean matches(char c) {
            boolean in = false;
            for (Range r : ranges) {
                if (c >= r.lo() && c <= r.hi()) {
                    in = true;
                    break;
                }
            }
            return in != negated;
        }
    }
    record Ref(String name) implements Expr { }
    record Seq(List<Expr> items) implements Expr {
        public Seq {
            items = List.copyOf(items);
        }
    }
    record Choice(List<Expr> alts) implements Expr {
        public Choice {
            alts = List.copyOf(alts);
        }
    }
    record Repeat(Expr body, boolean atLeastOne) implements Expr { }
    record Opt(Expr body) implements Expr { }
    record Pred(Expr body, boolean negative) implements Expr { }

    /** Sequence factory: one item is returned unwrapped. */
    static Expr seq(List<Expr> items) {
        return items.size() == 1 ? items.get(0) : new Seq(items);
    }

    /** Choice factory: one alternative is returned unwrapped. */
    static Expr choice(List<Expr> alts) {
        return alts.size() == 1 ? alts.get(0) : new Choice(alts);
    }

    /** Grammar-syntax text of an expression; parsing it again yields an equal expression. */
    static String render(Expr e) {
        return switch (e) {
            case Lit l -> "'" + escape(l.text(), "\\'") + "'";
            case Any a -> ".";
            case CharClass c -> "[" + (c.negated() ? "^" : "") + c.ranges().stream()
                    .map(r -> r.lo() == r.hi() ? escape(String.valueOf(r.lo()), "\\]^-[")
                            : escape(String.valueOf(r.lo()), "\\]^-[") + "-" + escape(String.valueOf(r.hi()), "\\]^-["))
                    .collect(Collectors.joining()) + "]";
            case Ref r -> r.name();
            case Seq s -> s.items().stream()
                    .map(x -> x instanceof Seq || x instanceof Choice ? "(" + render(x) + ")" : render(x))
                    .collect(Collectors.joining(" "));
            case Choice c -> c.alts().stream()
                    .map(x -> x instanceof Choice ? "(" + render(x) + ")" : render(x))
                    .collect(Collectors.joining(" / "));
            case Repeat r -> atom(r.body()) + (r.atLeastOne() ? "+" : "*");
            case Opt o -> atom(o.body()) + "?";
            case Pred p -> (p.negative() ? "!" : "&")
                    + (p.body() instanceof Seq || p.body() instanceof Choice ? "(" + render(p.body()) + ")" : render(p.body()));
        };
    }

    private static String atom(Expr x) {
        return x instanceof Lit || x instanceof Any || x instanceof CharClass || x instanceof Ref
                ? render(x) : "(" + render(x) + ")";
    }

    private static String escape(String s, String special) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (special.indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
