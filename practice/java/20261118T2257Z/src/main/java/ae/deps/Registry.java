package ae.deps;

import java.util.List;
import java.util.Optional;

/** Port: read-only view of published packages. */
public interface Registry {
    /** All published versions of {@code name}, highest first; empty if the package is unknown. */
    List<Version> versions(String name);

    /** The package at an exact version, if published. */
    Optional<Package> find(String name, Version version);
}
