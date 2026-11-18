package ae.jobs;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable description of a job to submit. {@code dependsOn} ids must already be submitted. */
public record JobSpec(String id, String type, String payload, Set<String> dependsOn, long runAt) {
    public JobSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(dependsOn, "dependsOn");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        // Sorted copy: deterministic journal encoding and an immutable, comparable value.
        TreeSet<String> sorted = new TreeSet<>(dependsOn);
        for (String dep : sorted) {
            if (dep.isBlank()) {
                throw new IllegalArgumentException("dependency id must not be blank");
            }
        }
        dependsOn = java.util.Collections.unmodifiableSet(sorted);
    }

    public static JobSpec of(String id, String type, String payload) {
        return new JobSpec(id, type, payload, Set.of(), 0L);
    }

    public JobSpec after(String... dependencies) {
        TreeSet<String> all = new TreeSet<>(dependsOn);
        all.addAll(java.util.Arrays.asList(dependencies));
        return new JobSpec(id, type, payload, all, runAt);
    }

    public JobSpec at(long earliestRunAt) {
        return new JobSpec(id, type, payload, dependsOn, earliestRunAt);
    }
}
