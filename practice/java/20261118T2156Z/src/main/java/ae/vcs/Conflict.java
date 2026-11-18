package ae.vcs;

/** A path the three-way merge cannot decide on its own. */
public record Conflict(String path, Kind kind) {
    public enum Kind { BOTH_CHANGED, CHANGED_DELETED, FILE_DIRECTORY }
}
