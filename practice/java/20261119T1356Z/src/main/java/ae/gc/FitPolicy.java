package ae.gc;

/** How {@link Heap} chooses a free extent for a new block. */
public enum FitPolicy {
    /** Lowest-address extent that is large enough. */
    FIRST_FIT,
    /** Smallest extent that is large enough; ties go to the lowest address. */
    BEST_FIT
}
