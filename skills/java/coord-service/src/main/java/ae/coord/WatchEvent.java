package ae.coord;

/** A one-shot notification delivered to a watcher. */
public record WatchEvent(Type type, String path) {
    /** Kinds of change a watch can report. */
    public enum Type { NODE_CREATED, NODE_DELETED, NODE_DATA_CHANGED, NODE_CHILDREN_CHANGED }
}
