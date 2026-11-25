package ae.cachesim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class HierarchyTest {

    private static Hierarchy small() {
        // L1: 128 B, 64 B lines, direct mapped (2 sets). L2: 1 KiB, 64 B lines, 2-way (8 sets).
        return new Hierarchy(
                new Cache(new CacheConfig(128, 64, 1, Policy.LRU, true, 0)),
                new Cache(new CacheConfig(1024, 64, 2, Policy.LRU, true, 0)));
    }

    @Test
    void amatLiteral() {
        Hierarchy h = small();
        for (long a : new long[] {0, 64, 0, 64}) h.access(a, false);
        // L1: miss miss hit hit. L2 sees 2 reads, both miss.
        assertEquals(2, h.l1().stats().misses());
        assertEquals(2, h.l2().stats().accesses());
        assertEquals(2, h.l2().stats().misses());
        // 1 + (2/4) * (10 + (2/2) * 100) = 56
        assertEquals(56.0, h.amat(1, 10, 100), 1e-12);
    }

    @Test
    void dirtyEvictionFlowsToL2AsWrite() {
        Hierarchy h = small();
        h.access(0, true); // L1 write miss: fill read -> L2 read miss
        h.access(128, false); // same L1 set 0 (line 2): evicts dirty line 0 -> L2 write hit; fill read of line 2
        CacheStats l2 = h.l2().stats();
        assertEquals(2, l2.reads());
        assertEquals(1, l2.writes());
        assertEquals(1, l2.writeHits()); // line 0 was filled into L2 first
        assertEquals(1, h.l1().stats().writebacks());
        assertEquals(0, h.l2().stats().memWrites()); // L2 holds it dirty, memory not yet written
        assertEquals(1, h.l2().flush());
    }

    @Test
    void writeThroughL1ForwardsEveryWrite() {
        Hierarchy h = new Hierarchy(
                new Cache(new CacheConfig(128, 64, 1, Policy.LRU, false, 0)),
                new Cache(new CacheConfig(1024, 64, 2, Policy.LRU, true, 0)));
        h.access(0, true); // L1 no-allocate miss -> L2 write miss, allocated dirty in L2
        h.access(0, false); // L1 fill -> L2 read hit
        h.access(4, true); // L1 write hit -> forwarded write to L2 hit
        assertEquals(new CacheStats(1, 2, 0, 1, 0, 0, 1, 2), h.l1().stats());
        assertEquals(new CacheStats(1, 2, 1, 1, 0, 0, 1, 0), h.l2().stats());
    }

    @Test
    void l2LineMustNotBeSmallerThanL1() {
        assertThrows(IllegalArgumentException.class, () -> new Hierarchy(
                new Cache(new CacheConfig(128, 64, 1, Policy.LRU, true, 0)),
                new Cache(new CacheConfig(1024, 32, 2, Policy.LRU, true, 0))));
    }

    @Test
    void conservationOnRandomTraffic() {
        Hierarchy h = small();
        Random r = new Random(11);
        long writes = 0;
        for (int i = 0; i < 20000; i++) {
            boolean w = r.nextInt(4) == 0;
            if (w) writes++;
            h.access(r.nextInt(8192), w);
        }
        CacheStats a = h.l1().stats();
        CacheStats b = h.l2().stats();
        assertEquals(20000, a.accesses());
        assertEquals(writes, a.writes());
        // every L1 miss reads L2 once; every L1 writeback writes L2 once
        assertEquals(a.misses(), b.reads());
        assertEquals(a.writebacks(), b.writes());
        assertEquals(a.memReads(), b.reads());
        assertEquals(a.memWrites(), b.writes());
        assertTrue(b.misses() <= b.accesses());
        assertTrue(h.amat(1, 10, 100) >= 1.0);
    }
}
