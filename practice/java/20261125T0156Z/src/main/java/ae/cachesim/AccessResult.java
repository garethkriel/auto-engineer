package ae.cachesim;

/**
 * Outcome of one cache access.
 *
 * @param hit the line was resident
 * @param fill the line was fetched from the next level
 * @param evictedLine line number (address / lineBytes) removed to make room, or {@link #NONE}
 * @param writeback the evicted line was dirty and is written to the next level
 * @param writeThrough the write is forwarded to the next level (write-through caches only)
 */
public record AccessResult(boolean hit, boolean fill, long evictedLine, boolean writeback, boolean writeThrough) {
    /** Marker for "no line evicted". */
    public static final long NONE = -1;
}
