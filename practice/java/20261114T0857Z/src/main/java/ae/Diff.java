package ae;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Result of comparing two {@link Snapshot}s. All lists are sorted and immutable.
 *
 * @param added    paths present only in the newer snapshot
 * @param removed  paths present only in the older snapshot
 * @param modified paths in both with different content hashes
 */
public record Diff(List<String> added, List<String> removed, List<String> modified) {
    public Diff {
        added = List.copyOf(Objects.requireNonNull(added));
        removed = List.copyOf(Objects.requireNonNull(removed));
        modified = List.copyOf(Objects.requireNonNull(modified));
    }

    /** @return true when nothing was added, removed or modified */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && modified.isEmpty();
    }

    /** One line per change: {@code + added}, {@code - removed}, {@code ~ modified}. */
    public String render() {
        if (isEmpty()) {
            return "no changes";
        }
        List<String> lines = new ArrayList<>();
        added.forEach(p -> lines.add("+ " + p));
        removed.forEach(p -> lines.add("- " + p));
        modified.forEach(p -> lines.add("~ " + p));
        return String.join("\n", lines);
    }
}
