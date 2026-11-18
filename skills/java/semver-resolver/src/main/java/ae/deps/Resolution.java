package ae.deps;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A resolved dependency set: one version per package and a dependencies-first install order. */
public record Resolution(Map<String, Version> versions, List<String> installOrder) {
    /** Canonical constructor: defensive immutable copies, sorted by name. */
    public Resolution {
        versions = Collections.unmodifiableMap(new TreeMap<>(versions));
        installOrder = List.copyOf(installOrder);
    }
}
