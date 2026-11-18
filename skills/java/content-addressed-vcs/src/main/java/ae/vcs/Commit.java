package ae.vcs;

import java.util.List;
import java.util.Objects;

/** A commit: a snapshot tree, its parents (first = branch the commit was made on), author, time and message. */
public record Commit(ObjectId tree, List<ObjectId> parents, String author, long time, String message) {
    public Commit {
        Objects.requireNonNull(tree);
        parents = List.copyOf(parents);
        Objects.requireNonNull(message);
        if (author == null || author.contains("\n")) {
            throw new IllegalArgumentException("author must be non-null and single-line");
        }
    }
}
