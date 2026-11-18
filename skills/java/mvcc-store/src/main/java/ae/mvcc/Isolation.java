package ae.mvcc;

/** Isolation level chosen when a transaction begins. */
public enum Isolation {
    /** First-committer-wins on written keys; write skew is possible. */
    SNAPSHOT,
    /** Snapshot plus validation of every read key and scanned range. */
    SERIALIZABLE
}
