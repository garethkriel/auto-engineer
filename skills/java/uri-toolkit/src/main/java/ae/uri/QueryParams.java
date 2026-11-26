package ae.uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable ordered multimap of application/x-www-form-urlencoded query parameters. */
public final class QueryParams {
    private record Pair(String name, String value) {}

    private static final QueryParams EMPTY = new QueryParams(List.of());

    private final List<Pair> pairs;

    private QueryParams(List<Pair> pairs) {
        this.pairs = pairs;
    }

    /** {@code of("a","1","b","2")}: name/value pairs in order. */
    public static QueryParams of(String... nameValues) {
        if (nameValues.length % 2 != 0) throw new IllegalArgumentException("odd number of arguments");
        List<Pair> l = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            l.add(new Pair(Objects.requireNonNull(nameValues[i]), Objects.requireNonNull(nameValues[i + 1])));
        }
        return l.isEmpty() ? EMPTY : new QueryParams(List.copyOf(l));
    }

    /** Parses a raw query; empty segments are skipped and a missing '=' means an empty value. */
    public static QueryParams parse(String raw) {
        Objects.requireNonNull(raw);
        List<Pair> l = new ArrayList<>();
        for (String part : raw.split("&", -1)) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String n = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            l.add(new Pair(PercentCodec.decodeForm(n), PercentCodec.decodeForm(v)));
        }
        return l.isEmpty() ? EMPTY : new QueryParams(List.copyOf(l));
    }

    public int size() {
        return pairs.size();
    }

    public Optional<String> get(String name) {
        for (Pair p : pairs) if (p.name.equals(name)) return Optional.of(p.value);
        return Optional.empty();
    }

    public List<String> getAll(String name) {
        List<String> out = new ArrayList<>();
        for (Pair p : pairs) if (p.name.equals(name)) out.add(p.value);
        return List.copyOf(out);
    }

    /** Appends a pair. */
    public QueryParams with(String name, String value) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(value);
        List<Pair> l = new ArrayList<>(pairs);
        l.add(new Pair(name, value));
        return new QueryParams(List.copyOf(l));
    }

    /** Replaces the first pair named {@code name} and drops the others; appends when absent. */
    public QueryParams set(String name, String value) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(value);
        List<Pair> l = new ArrayList<>();
        boolean placed = false;
        for (Pair p : pairs) {
            if (!p.name.equals(name)) {
                l.add(p);
            } else if (!placed) {
                l.add(new Pair(name, value));
                placed = true;
            }
        }
        if (!placed) l.add(new Pair(name, value));
        return new QueryParams(List.copyOf(l));
    }

    public QueryParams without(String name) {
        List<Pair> l = new ArrayList<>();
        for (Pair p : pairs) if (!p.name.equals(name)) l.add(p);
        return l.isEmpty() ? EMPTY : new QueryParams(List.copyOf(l));
    }

    private static String enc(String s) {
        return PercentCodec.encode(s, Chars::isUnreserved).replace("%20", "+");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Pair p : pairs) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(p.name)).append('=').append(enc(p.value));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof QueryParams q && pairs.equals(q.pairs);
    }

    @Override
    public int hashCode() {
        return pairs.hashCode();
    }
}
