package ae.vcs;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** In-memory refs. */
public final class MemoryRefs implements Refs {
    private final TreeMap<String, ObjectId> map = new TreeMap<>();

    @Override
    public synchronized Optional<ObjectId> get(String name) {
        Refs.checkName(name);
        return Optional.ofNullable(map.get(name));
    }

    @Override
    public synchronized void set(String name, ObjectId id) {
        Refs.checkName(name);
        map.put(name, java.util.Objects.requireNonNull(id));
    }

    @Override
    public synchronized List<String> names() {
        return List.copyOf(map.keySet());
    }
}
