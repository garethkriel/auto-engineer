package ae.vcs;

/** One path-level difference between two snapshots. */
public record Change(String path, Kind kind) {
    public enum Kind { ADDED, MODIFIED, DELETED }
}
