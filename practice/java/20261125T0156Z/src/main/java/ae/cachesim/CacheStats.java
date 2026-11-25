package ae.cachesim;

/** Immutable counters of a cache. Memory counters refer to the next level below this cache. */
public record CacheStats(
        long reads,
        long writes,
        long readHits,
        long writeHits,
        long evictions,
        long writebacks,
        long memReads,
        long memWrites) {

    public long accesses() {
        return reads + writes;
    }

    public long hits() {
        return readHits + writeHits;
    }

    public long misses() {
        return accesses() - hits();
    }

    /** Misses divided by accesses; 0 when there were no accesses. */
    public double missRate() {
        return accesses() == 0 ? 0.0 : (double) misses() / accesses();
    }
}
