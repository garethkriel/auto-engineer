package ae.datalog;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable result: predicate -> set of tuples. Equal models have equal contents regardless of mode. */
public final class Model extends AbstractMap<String, Set<List<String>>> {
    private final Map<String, Set<List<String>>> data;

    Model(Map<String, Set<List<String>>> src) {
        Map<String, Set<List<String>>> m = new TreeMap<>();
        for (Entry<String, Set<List<String>>> e : src.entrySet())
            if (!e.getValue().isEmpty())
                m.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        this.data = Collections.unmodifiableMap(m);
    }

    @Override
    public Set<Entry<String, Set<List<String>>>> entrySet() {
        return data.entrySet();
    }
}
