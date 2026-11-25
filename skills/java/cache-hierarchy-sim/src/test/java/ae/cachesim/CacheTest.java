package ae.cachesim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CacheTest {

    /** Fully associative single set with 1-byte lines: line number == address. */
    private static Cache fa(int ways, Policy p) {
        return new Cache(new CacheConfig(ways, 1, ways, p, true, 1));
    }

    private static long misses(Cache c, long[] trace) {
        for (long a : trace) c.access(a, false);
        return c.stats().misses();
    }

    @Test
    void geometryAndValidation() {
        CacheConfig c = new CacheConfig(1024, 64, 2, Policy.LRU, true, 0);
        assertEquals(8, c.sets());
        assertThrows(IllegalArgumentException.class, () -> new CacheConfig(1024, 48, 2, Policy.LRU, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new CacheConfig(1000, 64, 2, Policy.LRU, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new CacheConfig(1536, 64, 2, Policy.LRU, true, 0)); // 12 sets
        assertThrows(IllegalArgumentException.class, () -> new CacheConfig(192, 64, 3, Policy.PLRU, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new CacheConfig(1024, 64, 0, Policy.LRU, true, 0));
        assertThrows(NullPointerException.class, () -> new CacheConfig(1024, 64, 2, null, true, 0));
        assertThrows(IllegalArgumentException.class, () -> fa(2, Policy.LRU).access(-1, false));
    }

    @Test
    void firstAccessMissesThenHitsWithinLine() {
        Cache c = new Cache(new CacheConfig(1024, 64, 2, Policy.LRU, true, 0));
        assertFalse(c.access(0, false).hit());
        assertTrue(c.access(63, false).hit());
        assertFalse(c.access(64, false).hit());
        assertEquals(new TreeSet<>(List.of(0L, 1L)), c.residentLines());
        assertTrue(c.contains(100));
        assertFalse(c.contains(128));
    }

    @Test
    void plruDivergesFromLruOnFifthAndSixthLine() {
        // 4-way single set. Fill A..D (lines 0..3), then E(4), F(5), G(6).
        // LRU evicts 0, 1, 2. Tree PLRU evicts way0 (line 0), then way2 (line 2), then way1 (line 1).
        Cache lru = fa(4, Policy.LRU);
        Cache plru = fa(4, Policy.PLRU);
        for (long a = 0; a < 4; a++) {
            lru.access(a, false);
            plru.access(a, false);
        }
        assertEquals(0, lru.access(4, false).evictedLine());
        assertEquals(0, plru.access(4, false).evictedLine());
        assertEquals(1, lru.access(5, false).evictedLine());
        assertEquals(2, plru.access(5, false).evictedLine()); // the divergence
        assertEquals(2, lru.access(6, false).evictedLine());
        assertEquals(1, plru.access(6, false).evictedLine());
    }

    @Test
    void fifoIgnoresHitsButLruDoesNot() {
        Cache fifo = fa(2, Policy.FIFO);
        Cache lru = fa(2, Policy.LRU);
        for (Cache c : List.of(fifo, lru)) {
            c.access(1, false);
            c.access(2, false);
            assertTrue(c.access(1, false).hit()); // refresh 1 for LRU only
        }
        assertEquals(1, fifo.access(3, false).evictedLine());
        assertEquals(2, lru.access(3, false).evictedLine());
    }

    @Test
    void fifoShowsBeladyAnomaly() {
        long[] t = {1, 2, 3, 4, 1, 2, 5, 1, 2, 3, 4, 5};
        assertEquals(9, misses(fa(3, Policy.FIFO), t));
        assertEquals(10, misses(fa(4, Policy.FIFO), t));
        assertEquals(7, OptSimulator.misses(t, 3));
        assertEquals(6, OptSimulator.misses(t, 4));
    }

    @Test
    void writeBackAllocateCountsDirtyEviction() {
        Cache c = new Cache(new CacheConfig(64, 64, 1, Policy.LRU, true, 0)); // exactly one line
        AccessResult w = c.access(0, true);
        assertFalse(w.hit());
        assertTrue(w.fill());
        assertFalse(w.writeback());
        AccessResult r = c.access(64, false);
        assertEquals(0, r.evictedLine());
        assertTrue(r.writeback());
        AccessResult r2 = c.access(128, false); // evicts clean line 1
        assertEquals(1, r2.evictedLine());
        assertFalse(r2.writeback());
        CacheStats s = c.stats();
        assertEquals(new CacheStats(2, 1, 0, 0, 2, 1, 3, 1), s);
    }

    @Test
    void writeThroughNoAllocate() {
        Cache c = new Cache(new CacheConfig(64, 64, 1, Policy.LRU, false, 0));
        AccessResult miss = c.access(0, true);
        assertFalse(miss.hit());
        assertFalse(miss.fill());
        assertTrue(miss.writeThrough());
        assertTrue(c.residentLines().isEmpty());
        c.access(0, false); // fill
        AccessResult hit = c.access(8, true);
        assertTrue(hit.hit());
        assertTrue(hit.writeThrough());
        c.access(64, false); // evicts clean line
        assertEquals(new CacheStats(2, 2, 0, 1, 1, 0, 2, 2), c.stats());
    }

    @Test
    void flushWritesBackOnlyDirtyLinesOnce() {
        Cache c = new Cache(new CacheConfig(256, 64, 4, Policy.LRU, true, 0));
        c.access(0, true);
        c.access(64, false);
        c.access(128, true);
        assertEquals(2, c.flush());
        assertEquals(0, c.flush());
        assertEquals(2, c.stats().writebacks());
        assertEquals(2, c.stats().memWrites());
        assertEquals(3, c.residentLines().size());
    }

    @Test
    void matrixWalks() {
        // 32x32 ints (4 B) = 4096 B, rows are 128 B = 2 lines. Cache 1 KiB, 64 B lines, 2-way, 8 sets.
        Cache row = new Cache(new CacheConfig(1024, 64, 2, Policy.LRU, true, 0));
        for (int r = 0; r < 32; r++) for (int c = 0; c < 32; c++) row.access(r * 128L + c * 4L, false);
        assertEquals(64, row.stats().misses()); // 4096/64 cold misses, no reuse
        assertEquals(1024 - 64, row.stats().hits());

        // Column walk: line = 2r + c/16; for fixed c the 32 lines map to only 4 sets (2r mod 8), 8 lines each,
        // cycled in the same order through 2 ways: LRU misses every time.
        Cache col = new Cache(new CacheConfig(1024, 64, 2, Policy.LRU, true, 0));
        for (int c = 0; c < 32; c++) for (int r = 0; r < 32; r++) col.access(r * 128L + c * 4L, false);
        assertEquals(1024, col.stats().misses());

        // Same walk with a cache big enough for the whole matrix: 64 cold misses.
        Cache big = new Cache(new CacheConfig(4096, 64, 4, Policy.LRU, true, 0));
        for (int c = 0; c < 32; c++) for (int r = 0; r < 32; r++) big.access(r * 128L + c * 4L, false);
        assertEquals(64, big.stats().misses());
    }

    // ---- differential against naive oracle ----

    /** Naive per-set list model: index 0 is the next victim. */
    private static final class Oracle {
        final int sets, ways, lineBytes;
        final boolean lru, writeBack;
        final List<List<long[]>> data = new ArrayList<>(); // {line, dirty}
        long writebacks, memWrites;

        Oracle(CacheConfig c) {
            sets = c.sets();
            ways = c.ways();
            lineBytes = c.lineBytes();
            lru = c.policy() == Policy.LRU;
            writeBack = c.writeBack();
            for (int i = 0; i < sets; i++) data.add(new ArrayList<>());
        }

        AccessResult access(long addr, boolean write) {
            long line = addr / lineBytes;
            List<long[]> set = data.get((int) (line % sets));
            for (int i = 0; i < set.size(); i++) {
                if (set.get(i)[0] == line) {
                    long[] e = set.get(i);
                    boolean wt = false;
                    if (write) {
                        if (writeBack) e[1] = 1;
                        else {
                            wt = true;
                            memWrites++;
                        }
                    }
                    if (lru) {
                        set.remove(i);
                        set.add(e);
                    }
                    return new AccessResult(true, false, AccessResult.NONE, false, wt);
                }
            }
            if (write && !writeBack) {
                memWrites++;
                return new AccessResult(false, false, AccessResult.NONE, false, true);
            }
            long ev = AccessResult.NONE;
            boolean wb = false;
            if (set.size() == ways) {
                long[] v = set.remove(0);
                ev = v[0];
                if (v[1] == 1) {
                    wb = true;
                    writebacks++;
                    memWrites++;
                }
            }
            set.add(new long[] {line, write ? 1 : 0});
            return new AccessResult(false, true, ev, wb, false);
        }

        TreeSet<Long> resident() {
            TreeSet<Long> s = new TreeSet<>();
            for (List<long[]> l : data) for (long[] e : l) s.add(e[0]);
            return s;
        }
    }

    private static void differential(CacheConfig cfg, long seed, int steps, int span) {
        Cache c = new Cache(cfg);
        Oracle o = new Oracle(cfg);
        Random rnd = new Random(seed);
        for (int i = 0; i < steps; i++) {
            long addr = rnd.nextInt(span);
            boolean w = rnd.nextInt(3) == 0;
            AccessResult a = c.access(addr, w);
            AccessResult b = o.access(addr, w);
            assertEquals(b, a, "step " + i + " addr " + addr + " write " + w + " cfg " + cfg);
            if (i % 50 == 0) assertEquals(o.resident(), c.residentLines(), "resident at " + i);
        }
        assertEquals(o.resident(), c.residentLines());
        assertEquals(o.writebacks, c.stats().writebacks());
        assertEquals(o.memWrites, c.stats().memWrites());
        CacheStats s = c.stats();
        assertEquals(steps, s.accesses());
        assertEquals(s.hits() + s.misses(), s.accesses());
    }

    @Test
    void lruAndFifoMatchNaiveOracle() {
        int[][] shapes = {{512, 16, 1}, {512, 16, 2}, {512, 16, 4}, {512, 16, 32}, {256, 32, 8}};
        long seed = 100;
        for (int[] sh : shapes)
            for (Policy p : List.of(Policy.LRU, Policy.FIFO))
                for (boolean wb : List.of(true, false))
                    differential(new CacheConfig(sh[0], sh[1], sh[2], p, wb, 0), seed++, 4000, 2048);
    }

    @Test
    void twoWayPlruIsExactlyLru() {
        for (int seed = 0; seed < 5; seed++) {
            Cache a = new Cache(new CacheConfig(512, 16, 2, Policy.PLRU, true, 0));
            Cache b = new Cache(new CacheConfig(512, 16, 2, Policy.LRU, true, 0));
            Random r = new Random(seed);
            for (int i = 0; i < 5000; i++) {
                long addr = r.nextInt(4096);
                boolean w = r.nextBoolean();
                assertEquals(b.access(addr, w), a.access(addr, w), "step " + i);
            }
        }
    }

    @Test
    void lruStackPropertyAndOptLowerBound() {
        Random r = new Random(7);
        for (int round = 0; round < 20; round++) {
            long[] t = new long[400];
            for (int i = 0; i < t.length; i++) t[i] = r.nextInt(round % 2 == 0 ? 12 : 40);
            long prev = Long.MAX_VALUE;
            for (int ways = 1; ways <= 16; ways++) {
                long lru = misses(fa(ways, Policy.LRU), t);
                assertTrue(lru <= prev, "LRU misses must not grow with capacity, ways " + ways);
                prev = lru;
                long opt = OptSimulator.misses(t, ways);
                assertTrue(opt <= lru, "OPT <= LRU at " + ways);
                assertTrue(opt <= misses(fa(ways, Policy.FIFO), t));
                assertTrue(opt <= misses(new Cache(new CacheConfig(ways, 1, ways, Policy.RANDOM, true, round)), t));
            }
        }
    }

    @Test
    void optLiterals() {
        assertEquals(0, OptSimulator.misses(new long[0], 3));
        assertEquals(3, OptSimulator.misses(new long[] {1, 2, 3, 1, 2, 3}, 3));
        // capacity 2, cyclic 1,2,3: OPT keeps one of the pair -> 1,2,3 miss, then 1 hit, 2 miss, 3 hit... literal count
        assertEquals(4, OptSimulator.misses(new long[] {1, 2, 3, 1, 2, 3}, 2));
        assertThrows(IllegalArgumentException.class, () -> OptSimulator.misses(new long[] {1}, 0));
    }

    @Test
    void randomPolicyIsSeededAndStaysWithinCapacity() {
        long[] t = new long[2000];
        Random r = new Random(3);
        for (int i = 0; i < t.length; i++) t[i] = r.nextInt(64);
        long a = misses(new Cache(new CacheConfig(8, 1, 8, Policy.RANDOM, true, 42)), t);
        long b = misses(new Cache(new CacheConfig(8, 1, 8, Policy.RANDOM, true, 42)), t);
        assertEquals(a, b);
        Cache c = new Cache(new CacheConfig(8, 1, 8, Policy.RANDOM, true, 43));
        for (long x : t) {
            c.access(x, false);
            assertTrue(c.residentLines().size() <= 8);
        }
        assertTrue(c.residentLines().contains(t[t.length - 1]));
    }
}
