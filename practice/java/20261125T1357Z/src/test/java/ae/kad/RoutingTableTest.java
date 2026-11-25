package ae.kad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RoutingTableTest {
    // self = 0. Bucket 3 holds ids 8..15. Bucket 0 holds id 1. Bucket 1 holds 2,3.
    private static NodeId n(long v) {
        return new NodeId(v);
    }

    @Test
    void selfIsIgnored() {
        RoutingTable t = new RoutingTable(n(0), 2);
        assertEquals(Optional.empty(), t.touch(n(0)));
        assertEquals(0, t.size());
    }

    @Test
    void addsUntilBucketFullThenReturnsOldest() {
        RoutingTable t = new RoutingTable(n(0), 2);
        assertEquals(Optional.empty(), t.touch(n(8)));
        assertEquals(Optional.empty(), t.touch(n(9)));
        assertEquals(List.of(n(8), n(9)), t.bucket(3));
        assertEquals(Optional.of(n(8)), t.touch(n(10))); // full: 8 is least recently seen
        assertEquals(List.of(n(8), n(9)), t.bucket(3)); // 10 not admitted
        assertEquals(List.of(n(10)), t.replacements(3));
        assertFalse(t.contains(n(10)));
        assertEquals(2, t.size());
    }

    @Test
    void touchingKnownMovesToTailAndChangesOldest() {
        RoutingTable t = new RoutingTable(n(0), 2);
        t.touch(n(8));
        t.touch(n(9));
        assertEquals(Optional.empty(), t.touch(n(8))); // known: no ping needed
        assertEquals(List.of(n(9), n(8)), t.bucket(3));
        assertEquals(Optional.of(n(9)), t.touch(n(11)));
    }

    @Test
    void buckets_areIndependent() {
        RoutingTable t = new RoutingTable(n(0), 1);
        assertEquals(Optional.empty(), t.touch(n(1)));
        assertEquals(Optional.empty(), t.touch(n(2)));
        assertEquals(Optional.empty(), t.touch(n(8)));
        assertEquals(3, t.size());
        assertEquals(Optional.of(n(2)), t.touch(n(3)));
        assertEquals(List.of(n(1)), t.bucket(0));
        assertEquals(List.of(n(2)), t.bucket(1));
    }

    @Test
    void evictPromotesNewestReplacement() {
        RoutingTable t = new RoutingTable(n(0), 2);
        t.touch(n(8));
        t.touch(n(9));
        t.touch(n(10));
        t.touch(n(11));
        assertEquals(List.of(n(10), n(11)), t.replacements(3));
        t.evict(n(8));
        assertEquals(List.of(n(9), n(11)), t.bucket(3)); // newest replacement promoted
        assertEquals(List.of(n(10)), t.replacements(3));
        assertFalse(t.contains(n(8)));
    }

    @Test
    void evictWithoutReplacementShrinksAndUnknownIsNoop() {
        RoutingTable t = new RoutingTable(n(0), 2);
        t.touch(n(8));
        t.evict(n(8));
        assertEquals(0, t.size());
        t.evict(n(8));
        t.evict(n(0));
        assertEquals(0, t.size());
    }

    @Test
    void replacementCacheIsCappedAndDeduplicated() {
        RoutingTable t = new RoutingTable(n(0), 2);
        t.touch(n(8));
        t.touch(n(9));
        t.touch(n(10));
        t.touch(n(11));
        t.touch(n(10)); // duplicate: moves to tail instead of growing
        assertEquals(List.of(n(11), n(10)), t.replacements(3));
        t.touch(n(12)); // cap k=2: oldest replacement (11) dropped
        assertEquals(List.of(n(10), n(12)), t.replacements(3));
    }

    @Test
    void evictingReplacementOnlyRemovesItFromCache() {
        RoutingTable t = new RoutingTable(n(0), 1);
        t.touch(n(8));
        t.touch(n(9));
        t.evict(n(9));
        assertEquals(List.of(), t.replacements(3));
        assertEquals(List.of(n(8)), t.bucket(3));
    }

    @Test
    void closestMatchesBruteForceOracle() {
        Random r = new Random(7);
        NodeId self = n(r.nextLong());
        RoutingTable t = new RoutingTable(self, 4);
        List<NodeId> admitted = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            NodeId id = n(r.nextLong());
            t.touch(id);
        }
        for (int b = 0; b < 64; b++) admitted.addAll(t.bucket(b));
        assertEquals(admitted.size(), t.size());
        assertTrue(t.size() > 10);
        for (int trial = 0; trial < 50; trial++) {
            NodeId target = n(r.nextLong());
            List<NodeId> oracle = new ArrayList<>(admitted);
            oracle.sort(NodeId.closerTo(target));
            for (int count : new int[] {1, 5, 1000}) {
                List<NodeId> got = t.closest(target, count);
                assertEquals(oracle.subList(0, Math.min(count, oracle.size())), got);
            }
        }
        assertEquals(List.of(), t.closest(self, 0));
    }

    @Test
    void bucketsNeverExceedKAndOnlyHoldMatchingRange() {
        Random r = new Random(3);
        NodeId self = n(r.nextLong());
        RoutingTable t = new RoutingTable(self, 3);
        for (int i = 0; i < 2000; i++) t.touch(n(r.nextLong() >>> r.nextInt(40)));
        for (int b = 0; b < 64; b++) {
            assertTrue(t.bucket(b).size() <= 3);
            assertTrue(t.replacements(b).size() <= 3);
            for (NodeId id : t.bucket(b)) assertEquals(b, self.bucketIndex(id));
            for (NodeId id : t.replacements(b)) assertEquals(b, self.bucketIndex(id));
        }
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new RoutingTable(n(0), 0));
        assertThrows(NullPointerException.class, () -> new RoutingTable(null, 1));
    }
}
