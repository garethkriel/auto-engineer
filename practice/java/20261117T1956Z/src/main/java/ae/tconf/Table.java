package ae.tconf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** An ordered key to {@link Value} map. Only the parser can mutate it. */
public final class Table implements Value {
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_-]+");
    private final Map<String, Value> entries = new LinkedHashMap<>();

    Table() {
    }

    Value raw(String key) {
        return entries.get(key);
    }

    void put(String key, Value v) {
        entries.put(key, v);
    }

    /** Looks up a path of keys; an empty path returns this table. Missing or non-table prefixes give empty. */
    public Optional<Value> get(String... path) {
        Value cur = this;
        for (String k : path) {
            if (!(cur instanceof Table t)) {
                return Optional.empty();
            }
            cur = t.entries.get(k);
            if (cur == null) {
                return Optional.empty();
            }
        }
        return Optional.of(cur);
    }

    /** Keys in insertion order. */
    public List<String> keys() {
        return new ArrayList<>(entries.keySet());
    }

    public int size() {
        return entries.size();
    }

    static String renderKey(String k) {
        return BARE.matcher(k).matches() ? k : new Value.Str(k).toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Value> e : entries.entrySet()) {
            sb.append(first ? "" : ", ").append(renderKey(e.getKey())).append('=').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }
}
