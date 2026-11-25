package ae.cachesim;

import java.util.Objects;

/** Two-level cache: L1 misses, writebacks and write-throughs are replayed as accesses to L2. */
public final class Hierarchy {
    private final Cache l1;
    private final Cache l2;

    public Hierarchy(Cache l1, Cache l2) {
        this.l1 = Objects.requireNonNull(l1);
        this.l2 = Objects.requireNonNull(l2);
        if (l2.lineBytes() < l1.lineBytes()) {
            throw new IllegalArgumentException("L2 line must not be smaller than L1 line");
        }
    }

    public Cache l1() {
        return l1;
    }

    public Cache l2() {
        return l2;
    }

    /** Runs one access through L1 and, as needed, L2. Order: writeback of the victim, then fill or write-through. */
    public void access(long addr, boolean write) {
        AccessResult r = l1.access(addr, write);
        if (r.writeback()) {
            l2.access(r.evictedLine() * l1.lineBytes(), true);
        }
        if (r.fill()) {
            l2.access(addr, false);
        }
        if (r.writeThrough()) {
            l2.access(addr, true);
        }
    }

    /** Average memory access time: {@code l1 + l1MissRate * (l2 + l2LocalMissRate * mem)}. */
    public double amat(double l1Latency, double l2Latency, double memLatency) {
        double m1 = l1.stats().missRate();
        double m2 = l2.stats().missRate();
        return l1Latency + m1 * (l2Latency + m2 * memLatency);
    }
}
