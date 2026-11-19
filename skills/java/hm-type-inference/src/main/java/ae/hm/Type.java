package ae.hm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Monotypes: variables and constructor applications ({@code Int}, {@code ->}, {@code (,)}, {@code []}). */
public sealed interface Type {

    record TVar(int id) implements Type {}

    record TCon(String name, List<Type> args) implements Type {
        public TCon {
            args = List.copyOf(args);
        }
    }

    Type INT = new TCon("Int", List.of());
    Type BOOL = new TCon("Bool", List.of());

    static Type fun(Type from, Type to) { return new TCon("->", List.of(from, to)); }

    static Type pair(Type l, Type r) { return new TCon("(,)", List.of(l, r)); }

    static Type list(Type elem) { return new TCon("[]", List.of(elem)); }

    /** Free variables in order of first appearance, left to right. */
    static Set<Integer> freeVars(Type t) {
        Set<Integer> out = new LinkedHashSet<>();
        collect(t, out);
        return out;
    }

    private static void collect(Type t, Set<Integer> out) {
        switch (t) {
            case TVar v -> out.add(v.id());
            case TCon c -> c.args().forEach(a -> collect(a, out));
        }
    }

    /** Renames variables to 0, 1, 2 ... by first appearance. */
    static Type normalize(Type t) {
        Map<Integer, Type> names = new HashMap<>();
        for (int id : freeVars(t)) {
            names.put(id, new TVar(names.size()));
        }
        return rename(t, names);
    }

    private static Type rename(Type t, Map<Integer, Type> names) {
        return switch (t) {
            case TVar v -> names.get(v.id());
            case TCon c -> {
                List<Type> args = new ArrayList<>();
                for (Type a : c.args()) {
                    args.add(rename(a, names));
                }
                yield new TCon(c.name(), args);
            }
        };
    }

    /** Pretty-prints several types with one shared naming (a, b, c ... by first appearance across all of them). */
    static List<String> showAll(Type... types) {
        Map<Integer, String> names = new HashMap<>();
        List<String> out = new ArrayList<>();
        for (Type t : types) {
            out.add(render(t, 0, names));
        }
        return out;
    }

    static String show(Type t) {
        return showAll(t).get(0);
    }

    private static String varName(int index) {
        char letter = (char) ('a' + index % 26);
        return index < 26 ? String.valueOf(letter) : letter + Integer.toString(index / 26);
    }

    /** prec 0: top level, 1: left operand of an arrow. */
    private static String render(Type t, int prec, Map<Integer, String> names) {
        switch (t) {
            case TVar v -> {
                String name = names.get(v.id());
                if (name == null) {
                    name = varName(names.size());
                    names.put(v.id(), name);
                }
                return name;
            }
            case TCon c -> {
                switch (c.name()) {
                    case "->" -> {
                        String from = render(c.args().get(0), 1, names);
                        String to = render(c.args().get(1), 0, names);
                        return prec > 0 ? "(" + from + " -> " + to + ")" : from + " -> " + to;
                    }
                    case "(,)" -> {
                        String l = render(c.args().get(0), 0, names);
                        String r = render(c.args().get(1), 0, names);
                        return "(" + l + ", " + r + ")";
                    }
                    case "[]" -> {
                        return "[" + render(c.args().get(0), 0, names) + "]";
                    }
                    default -> {
                        return c.name();
                    }
                }
            }
        }
    }
}
