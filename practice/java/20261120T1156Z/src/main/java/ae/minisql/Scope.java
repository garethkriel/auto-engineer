package ae.minisql;

import ae.minisql.Ast.Col;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ordered list of (alias, column) pairs describing the layout of a row; resolves column references. */
final class Scope {
    record Entry(String alias, String name) {}

    private final List<Entry> entries;
    private final Map<Col, Integer> cache = new HashMap<>();

    Scope(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    static Scope concat(Scope a, Scope b) {
        List<Entry> all = new ArrayList<>(a.entries);
        all.addAll(b.entries);
        return new Scope(all);
    }

    List<Entry> entries() {
        return entries;
    }

    int size() {
        return entries.size();
    }

    boolean hasAlias(String alias) {
        for (Entry e : entries) if (e.alias.equals(alias)) return true;
        return false;
    }

    /** Index of the referenced column; throws "unknown column" or "ambiguous column". */
    int resolve(Col c) {
        Integer hit = cache.get(c);
        if (hit != null) return hit;
        int found = -1;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.name.equals(c.name()) && (c.table() == null || e.alias.equals(c.table()))) {
                if (found >= 0) throw new SqlException("ambiguous column " + c);
                found = i;
            }
        }
        if (found < 0) throw new SqlException("unknown column " + c);
        cache.put(c, found);
        return found;
    }
}
