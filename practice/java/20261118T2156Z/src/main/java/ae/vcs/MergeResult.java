package ae.vcs;

import java.util.List;

/** Outcome of {@link Repo#merge}. */
public sealed interface MergeResult {
    /** The target already contains the other branch; nothing changed. */
    record UpToDate() implements MergeResult { }

    /** The target branch moved forward to an existing commit. */
    record FastForward(ObjectId commit) implements MergeResult { }

    /** A new merge commit with parents (target, other) was created. */
    record Merged(ObjectId commit) implements MergeResult { }

    /** Nothing was written; the conflicts are sorted by path. */
    record Conflicted(List<Conflict> conflicts) implements MergeResult {
        public Conflicted {
            conflicts = List.copyOf(conflicts);
        }
    }
}
