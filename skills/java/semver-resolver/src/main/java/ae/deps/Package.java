package ae.deps;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One published package version and its dependency constraints. */
public record Package(String name, Version version, Map<String, Constraint> deps) {
    /** Canonical constructor: copies deps into a sorted, immutable map. */
    public Package {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        deps = java.util.Collections.unmodifiableMap(new TreeMap<>(deps));
    }

    /** Package names are lower-case words made of letters, digits, '.', '_' and '-'. */
    static void checkName(String name) {
        if (!name.matches("[a-z][a-z0-9_.-]*")) throw new IllegalArgumentException("bad package name '" + name + "'");
    }
}
