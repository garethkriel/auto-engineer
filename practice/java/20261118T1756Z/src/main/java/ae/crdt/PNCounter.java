package ae.crdt;

/** Counter supporting decrements: a pair of grow-only counters (increments, decrements). */
public record PNCounter(GCounter pos, GCounter neg) {
    private static final PNCounter EMPTY = new PNCounter(GCounter.empty(), GCounter.empty());

    public static PNCounter empty() {
        return EMPTY;
    }

    public PNCounter increment(String replica, long n) {
        return new PNCounter(pos.increment(replica, n), neg);
    }

    public PNCounter decrement(String replica, long n) {
        return new PNCounter(pos, neg.increment(replica, n));
    }

    public long value() {
        return Math.subtractExact(pos.value(), neg.value());
    }

    public PNCounter merge(PNCounter other) {
        return new PNCounter(pos.merge(other.pos), neg.merge(other.neg));
    }
}
