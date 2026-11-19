package ae.gc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeapTest {

    private static Heap heap(int cap, FitPolicy p) {
        return new Heap(cap, p, false);
    }

    @Test
    void allocatesSequentiallyFromAddressZero() {
        Heap h = heap(100, FitPolicy.FIRST_FIT);
        int a = h.alloc(2, 1); // size 4
        int b = h.alloc(0, 0); // size 1
        int c = h.alloc(5, 0); // size 6
        assertEquals(1, a);
        assertEquals(2, b);
        assertEquals(0, h.addressOf(a));
        assertEquals(4, h.addressOf(b));
        assertEquals(5, h.addressOf(c));
        assertEquals(4, h.sizeOf(a));
        Heap.Stats s = h.stats();
        assertEquals(11, s.usedWords());
        assertEquals(89, s.freeWords());
        assertEquals(1, s.freeExtents());
        assertEquals(3, s.liveObjects());
        h.checkInvariants();
    }

    @Test
    void newObjectsAreZeroedAndAccessible() {
        Heap h = heap(50, FitPolicy.FIRST_FIT);
        int a = h.alloc(3, 2);
        for (int i = 0; i < 3; i++) assertEquals(0, h.getData(a, i));
        for (int i = 0; i < 2; i++) assertEquals(0, h.getRef(a, i));
        h.setData(a, 1, -77);
        assertEquals(-77, h.getData(a, 1));
        assertEquals(0, h.getData(a, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> h.getData(a, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> h.setData(a, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> h.getRef(a, 2));
    }

    @Test
    void rejectsBadArguments() {
        Heap h = heap(10, FitPolicy.FIRST_FIT);
        assertThrows(IllegalArgumentException.class, () -> h.alloc(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> h.alloc(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Heap(0, FitPolicy.FIRST_FIT, false));
        assertThrows(NullPointerException.class, () -> new Heap(5, null, false));
        assertThrows(IllegalStateException.class, () -> h.getData(99, 0));
        assertThrows(IllegalStateException.class, () -> h.addRoot(5));
        assertThrows(HeapFullException.class, () -> h.alloc(10, 0)); // size 11 > 10
        assertDoesNotThrow(() -> h.alloc(9, 0)); // size 10 exactly fits
        assertEquals(0, h.stats().freeWords());
    }

    /** Builds layout [A:3][B:2][C:5][D:2][E:4] in a heap of capacity 20 with free tail 4. */
    private Heap fragmented(FitPolicy p, int[] out) {
        Heap h = heap(21, p);
        int a = h.alloc(2, 0); // size 3 @0
        int b = h.alloc(1, 0); // size 2 @3
        int c = h.alloc(4, 0); // size 5 @5
        int d = h.alloc(1, 0); // size 2 @10
        int e = h.alloc(3, 0); // size 4 @12
        // tail free: 16..21 (5 words)
        h.addRoot(a);
        h.addRoot(d);
        h.addRoot(e);
        // b and c are garbage: gc frees [3,10) as ONE coalesced extent of 7
        Heap.GcResult r = h.gc();
        assertEquals(2, r.freedObjects());
        assertEquals(7, r.freedWords());
        out[0] = a;
        out[1] = d;
        out[2] = e;
        return h;
    }

    @Test
    void gcCoalescesAdjacentFreedBlocks() {
        int[] ids = new int[3];
        Heap h = fragmented(FitPolicy.FIRST_FIT, ids);
        Heap.Stats s = h.stats();
        assertEquals(2, s.freeExtents()); // [3,10) and [16,21)
        assertEquals(7, s.largestFree());
        assertEquals(12, s.freeWords());
        h.checkInvariants();
    }

    @Test
    void firstFitTakesLowestAddressThatFits() {
        int[] ids = new int[3];
        Heap h = fragmented(FitPolicy.FIRST_FIT, ids);
        int x = h.alloc(2, 0); // size 3 fits in [3,10) (7) and [16,21) (5): first -> 3
        assertEquals(3, h.addressOf(x));
        int y = h.alloc(3, 0); // size 4, remaining [6,10)=4 -> 6
        assertEquals(6, h.addressOf(y));
        h.checkInvariants();
    }

    @Test
    void bestFitTakesSmallestExtentThatFits() {
        int[] ids = new int[3];
        Heap h = fragmented(FitPolicy.BEST_FIT, ids);
        int x = h.alloc(2, 0); // size 3: extents 7@3 and 5@16 -> best is 5@16
        assertEquals(16, h.addressOf(x));
        int y = h.alloc(1, 0); // size 2: extents 7@3 and 2@19 -> best 2@19
        assertEquals(19, h.addressOf(y));
        assertEquals(1, h.stats().freeExtents());
        h.checkInvariants();
    }

    @Test
    void bestFitTiesGoToLowestAddress() {
        Heap h = heap(13, FitPolicy.BEST_FIT);
        int a = h.alloc(3, 0); // 4 @0
        int b = h.alloc(1, 0); // 2 @4
        int c = h.alloc(3, 0); // 4 @6
        int d = h.alloc(1, 0); // 2 @10
        int e = h.alloc(0, 0); // 1 @12, fills heap
        h.addRoot(b);
        h.addRoot(d);
        h.addRoot(e);
        // a and c garbage -> two extents of 4 at 0 and 6
        h.gc();
        assertEquals(2, h.stats().freeExtents());
        int x = h.alloc(3, 0); // size 4
        assertEquals(0, h.addressOf(x));
        int y = h.alloc(3, 0);
        assertEquals(6, h.addressOf(y));
    }

    @Test
    void freeingBetweenLiveBlocksKeepsSeparateExtents() {
        Heap h = heap(10, FitPolicy.FIRST_FIT);
        int a = h.alloc(1, 0); // @0 size 2
        int b = h.alloc(1, 0); // @2
        int c = h.alloc(1, 0); // @4
        h.addRoot(a);
        h.addRoot(c);
        h.gc(); // frees b only
        Heap.Stats s = h.stats();
        assertEquals(2, s.freeExtents()); // [2,4) and [6,10)
        assertEquals(4, s.largestFree()); // tail [6,10)
        assertFalse(h.isLive(b));
        h.checkInvariants();
    }

    @Test
    void coalescesWithBothNeighboursWhenMiddleFreed() {
        Heap h = heap(12, FitPolicy.FIRST_FIT);
        int a = h.alloc(1, 0); // @0 2
        int b = h.alloc(1, 0); // @2 2
        int c = h.alloc(1, 0); // @4 2
        int d = h.alloc(1, 0); // @6 2, tail [8,12)
        h.addRoot(a);
        h.addRoot(b);
        h.addRoot(c);
        h.addRoot(d);
        h.removeRoot(a);
        h.removeRoot(c);
        h.gc(); // a and c freed: extents [0,2) [4,6) [8,12)
        assertEquals(3, h.stats().freeExtents());
        h.removeRoot(b);
        h.removeRoot(d);
        h.gc(); // everything free: one extent
        assertEquals(1, h.stats().freeExtents());
        assertEquals(12, h.stats().largestFree());
        assertEquals(0, h.stats().liveObjects());
        h.checkInvariants();
    }

    @Test
    void gcKeepsReachableGraphAndFreesUnreachableCycle() {
        Heap h = heap(100, FitPolicy.FIRST_FIT);
        int root = h.alloc(1, 1);
        int mid = h.alloc(1, 1);
        int leaf = h.alloc(1, 0);
        h.setRef(root, 0, mid);
        h.setRef(mid, 0, leaf);
        h.setData(leaf, 0, 42);
        int c1 = h.alloc(0, 1);
        int c2 = h.alloc(0, 1);
        h.setRef(c1, 0, c2);
        h.setRef(c2, 0, c1); // cycle, unreachable
        h.addRoot(root);
        List<Integer> freed = new ArrayList<>();
        h.addFreeListener(freed::addAll);
        Heap.GcResult r = h.gc();
        assertEquals(2, r.freedObjects());
        assertEquals(4, r.freedWords()); // two blocks of size 2
        assertEquals(List.of(c1, c2), freed);
        assertTrue(h.isLive(leaf));
        assertEquals(42, h.getData(leaf, 0));
        assertFalse(h.isLive(c1));
        assertThrows(IllegalStateException.class, () -> h.getRef(c1, 0));
        assertEquals(List.of(root, mid, leaf), h.liveHandles());
        h.checkInvariants();
    }

    @Test
    void reachableCycleSurvivesAndSelfReferenceWorks() {
        Heap h = heap(50, FitPolicy.FIRST_FIT);
        int a = h.alloc(0, 1);
        int b = h.alloc(0, 1);
        h.setRef(a, 0, b);
        h.setRef(b, 0, a);
        int self = h.alloc(0, 1);
        h.setRef(self, 0, self);
        h.addRoot(a);
        assertEquals(1, h.gc().freedObjects()); // only self
        assertTrue(h.isLive(a));
        assertTrue(h.isLive(b));
        assertFalse(h.isLive(self));
    }

    @Test
    void deepChainDoesNotOverflowStack() {
        int n = 100_000;
        Heap h = heap(n * 2 + 10, FitPolicy.FIRST_FIT);
        int first = h.alloc(0, 1);
        int prev = first;
        for (int i = 1; i < n; i++) {
            int cur = h.alloc(0, 1);
            h.setRef(prev, 0, cur);
            prev = cur;
        }
        h.addRoot(first);
        assertEquals(0, h.gc().freedObjects());
        assertEquals(n, h.stats().liveObjects());
        h.removeRoot(first);
        assertEquals(n, h.gc().freedObjects());
        assertEquals(1, h.stats().freeExtents());
    }

    @Test
    void compactSlidesObjectsPreservingContentAndRefs() {
        Heap h = heap(30, FitPolicy.FIRST_FIT);
        int a = h.alloc(2, 1); // @0 size 4
        int junk = h.alloc(3, 0); // @4 size 4
        int b = h.alloc(1, 1); // @8 size 3
        int junk2 = h.alloc(0, 0); // @11 size 1
        int c = h.alloc(2, 0); // @12 size 3
        h.setData(a, 0, 11);
        h.setData(a, 1, 12);
        h.setRef(a, 0, b);
        h.setData(b, 0, 21);
        h.setRef(b, 0, c);
        h.setData(c, 1, 31);
        h.addRoot(a);
        h.gc();
        assertEquals(List.of(a, b, c), h.liveHandles());
        // free: [4,8), [11,12) and the tail [15,30)
        assertEquals(3, h.stats().freeExtents());
        int moved = h.compact();
        assertEquals(2, moved); // b and c move; a already at 0
        assertEquals(0, h.addressOf(a));
        assertEquals(4, h.addressOf(b));
        assertEquals(7, h.addressOf(c));
        assertEquals(11, h.getData(a, 0));
        assertEquals(12, h.getData(a, 1));
        assertEquals(b, h.getRef(a, 0));
        assertEquals(21, h.getData(b, 0));
        assertEquals(c, h.getRef(b, 0));
        assertEquals(31, h.getData(c, 1));
        Heap.Stats s = h.stats();
        assertEquals(1, s.freeExtents());
        assertEquals(30 - 10, s.largestFree());
        h.checkInvariants();
        assertEquals(0, h.compact()); // idempotent
    }

    @Test
    void compactOnEmptyAndFullHeaps() {
        Heap h = heap(10, FitPolicy.BEST_FIT);
        assertEquals(0, h.compact());
        h.alloc(9, 0);
        assertEquals(0, h.compact());
        assertEquals(0, h.stats().freeExtents());
        h.checkInvariants();
    }

    @Test
    void autoCollectRunsGcThenCompactOnlyWhenNeeded() {
        Heap h = new Heap(12, FitPolicy.FIRST_FIT, true);
        int a = h.alloc(1, 0); // @0 2
        int b = h.alloc(1, 0); // @2
        int c = h.alloc(1, 0); // @4
        int d = h.alloc(1, 0); // @6
        int e = h.alloc(1, 0); // @8
        int f = h.alloc(1, 0); // @10; heap now full
        h.addRoot(a);
        h.addRoot(c);
        h.addRoot(e);
        // b, d, f garbage. need 4 contiguous: gc frees 3 holes of 2 -> still no fit -> compact
        int g = h.alloc(3, 0); // size 4
        assertTrue(h.isLive(g));
        assertFalse(h.isLive(b));
        assertFalse(h.isLive(d));
        assertFalse(h.isLive(f));
        assertEquals(6, h.addressOf(g)); // after compaction a,c,e at 0,2,4
        assertEquals(2, h.addressOf(c));
        assertEquals(4, h.addressOf(e));
        h.checkInvariants();
    }

    @Test
    void autoCollectSkipsCompactWhenGcSuffices() {
        Heap h = new Heap(10, FitPolicy.FIRST_FIT, true);
        int a = h.alloc(1, 0); // @0
        int b = h.alloc(1, 0); // @2
        int c = h.alloc(1, 0); // @4
        h.alloc(1, 0); // @6
        h.alloc(1, 0); // @8, full
        h.addRoot(a);
        h.addRoot(c);
        h.addRoot(b);
        // d,e garbage -> gc frees [6,10) = 4 => fits without compaction
        int g = h.alloc(3, 0);
        assertEquals(6, h.addressOf(g));
        assertEquals(0, h.addressOf(a));
        assertEquals(4, h.addressOf(c));
    }

    @Test
    void failedAllocationLeavesLiveObjectsIntact() {
        Heap h = new Heap(10, FitPolicy.FIRST_FIT, true);
        int a = h.alloc(3, 0);
        int b = h.alloc(3, 0);
        h.setData(a, 2, 5);
        h.addRoot(a);
        h.addRoot(b);
        assertThrows(HeapFullException.class, () -> h.alloc(4, 0)); // need 5, free 2
        assertEquals(5, h.getData(a, 2));
        assertTrue(h.isLive(b));
        assertEquals(2, h.stats().freeWords());
        h.checkInvariants();
        // without autoCollect garbage stays and allocation fails
        Heap n = new Heap(10, FitPolicy.FIRST_FIT, false);
        n.alloc(8, 0);
        assertThrows(HeapFullException.class, () -> n.alloc(1, 0));
        assertEquals(1, n.stats().liveObjects());
    }

    @Test
    void rootsAreSetsAndMustBeLive() {
        Heap h = heap(20, FitPolicy.FIRST_FIT);
        int a = h.alloc(0, 0);
        h.addRoot(a);
        h.addRoot(a);
        h.removeRoot(a);
        assertEquals(1, h.gc().freedObjects());
        assertThrows(IllegalStateException.class, () -> h.addRoot(a));
        assertDoesNotThrow(() -> h.removeRoot(a)); // removing a non-root is a no-op
    }

    @Test
    void setRefValidatesTarget() {
        Heap h = heap(20, FitPolicy.FIRST_FIT);
        int a = h.alloc(0, 1);
        assertThrows(IllegalStateException.class, () -> h.setRef(a, 0, 77));
        h.setRef(a, 0, a);
        assertEquals(a, h.getRef(a, 0));
        h.setRef(a, 0, 0);
        assertEquals(0, h.getRef(a, 0));
    }

    @Test
    void handlesAreNeverReused() {
        Heap h = heap(20, FitPolicy.FIRST_FIT);
        int a = h.alloc(0, 0);
        h.gc();
        int b = h.alloc(0, 0);
        assertEquals(1, a);
        assertEquals(2, b);
        assertFalse(h.isLive(a));
    }

    @Test
    void listenerSeesConsistentHeapAndAscendingHandles() {
        Heap h = heap(40, FitPolicy.FIRST_FIT);
        for (int i = 0; i < 5; i++) h.alloc(1, 0);
        h.addRoot(3);
        List<List<Integer>> calls = new ArrayList<>();
        h.addFreeListener(hs -> {
            h.checkInvariants();
            assertEquals(1, h.stats().liveObjects());
            calls.add(new ArrayList<>(hs));
        });
        h.gc();
        assertEquals(List.of(List.of(1, 2, 4, 5)), calls);
        h.gc();
        assertEquals(1, calls.size()); // nothing freed -> no call
    }
}
