package ae.deps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Adapter: a mutable in-memory {@link Registry}. */
public final class InMemoryRegistry implements Registry {
    private final Map<String, Map<Version, Package>> packages = new HashMap<>();

    /**
     * Publishes {@code name@version}.
     *
     * @param deps alternating dependency name and constraint text
     * @return this, for chaining
     * @throws IllegalArgumentException on a bad name/version/constraint, odd {@code deps}, a repeated dependency
     *     or a duplicate publication (the registry is left unchanged)
     */
    public InMemoryRegistry add(String name, String version, String... deps) {
        if (deps.length % 2 != 0) throw new IllegalArgumentException("deps must be name/constraint pairs");
        Map<String, Constraint> d = new TreeMap<>();
        for (int i = 0; i < deps.length; i += 2) {
            Package.checkName(deps[i]);
            if (d.put(deps[i], Constraint.parse(deps[i + 1])) != null) {
                throw new IllegalArgumentException("duplicate dependency " + deps[i]);
            }
        }
        return add(new Package(name, Version.parse(version), d));
    }

    /** Publishes a prepared package; duplicates are rejected. */
    public InMemoryRegistry add(Package p) {
        Package.checkName(p.name());
        Map<Version, Package> byVersion = packages.computeIfAbsent(p.name(), k -> new TreeMap<>());
        if (byVersion.containsKey(p.version())) {
            throw new IllegalArgumentException("already published: " + p.name() + "@" + p.version());
        }
        byVersion.put(p.version(), p);
        return this;
    }

    @Override
    public List<Version> versions(String name) {
        Map<Version, Package> m = packages.get(name);
        if (m == null) return List.of();
        List<Version> out = new ArrayList<>(m.keySet());
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    @Override
    public Optional<Package> find(String name, Version version) {
        Map<Version, Package> m = packages.get(name);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(version));
    }
}
