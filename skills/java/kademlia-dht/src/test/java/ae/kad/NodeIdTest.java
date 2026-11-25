package ae.kad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class NodeIdTest {
    @Test
    void distanceIsXor() {
        assertEquals(0b0110L, new NodeId(0b1010).distance(new NodeId(0b1100)));
    }

    @Test
    void bucketIndexIsHighestDifferingBit() {
        NodeId a = new NodeId(0);
        assertEquals(-1, a.bucketIndex(a));
        assertEquals(0, a.bucketIndex(new NodeId(1)));
        assertEquals(1, a.bucketIndex(new NodeId(2)));
        assertEquals(1, a.bucketIndex(new NodeId(3)));
        assertEquals(62, a.bucketIndex(new NodeId(Long.MAX_VALUE)));
        assertEquals(63, a.bucketIndex(new NodeId(Long.MIN_VALUE)));
        assertEquals(63, a.bucketIndex(new NodeId(-1L)));
    }

    @Test
    void compareByDistanceIsUnsigned() {
        NodeId target = new NodeId(0);
        NodeId small = new NodeId(5);
        NodeId huge = new NodeId(Long.MIN_VALUE); // distance 2^63, "negative" as signed long
        assertTrue(NodeId.closerTo(target).compare(small, huge) < 0);
        assertTrue(NodeId.closerTo(target).compare(huge, small) > 0);
        assertEquals(0, NodeId.closerTo(target).compare(small, small));
    }

    @Test
    void metricAxiomsOnRandomIds() {
        Random r = new Random(1);
        for (int i = 0; i < 500; i++) {
            NodeId a = new NodeId(r.nextLong()), b = new NodeId(r.nextLong()), c = new NodeId(r.nextLong());
            assertEquals(a.distance(b), b.distance(a));
            assertEquals(0, a.distance(a));
            // XOR is a metric: d(a,c) <= d(a,b) + d(b,c) (use BigInteger-free unsigned check via xor identity)
            assertEquals(a.distance(c), a.distance(b) ^ b.distance(c));
            // unique-closest property: for fixed target and distance there is exactly one id
            assertEquals(a, new NodeId(a.distance(b) ^ b.value()));
            assertEquals(a.bucketIndex(b), b.bucketIndex(a));
        }
    }
}
