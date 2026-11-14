package pc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersistentQueueTest {
    @Test
    void emptyQueue() {
        PersistentQueue<String> q = PersistentQueue.empty();
        assertTrue(q.isEmpty());
        assertTrue(q.peek().isEmpty());
        assertThrows(IllegalStateException.class, q::dequeue);
        assertEquals("[]", q.toString());
    }

    @Test
    void fifoOrder() {
        PersistentQueue<Integer> q = PersistentQueue.<Integer>empty().enqueue(1).enqueue(2).enqueue(3);
        assertEquals(1, q.peek().orElseThrow());
        var d = q.dequeue();
        assertEquals(1, d.head());
        assertEquals(List.of(2, 3), d.rest().stream().toList());
        assertEquals(2, d.rest().size());
    }

    @Test
    void interleavedOperationsKeepOrder() {
        PersistentQueue<Integer> q = PersistentQueue.<Integer>empty().enqueue(1).enqueue(2);
        var d1 = q.dequeue();
        PersistentQueue<Integer> q2 = d1.rest().enqueue(3).enqueue(4);
        var d2 = q2.dequeue();
        assertEquals(2, d2.head());
        assertEquals(List.of(3, 4), d2.rest().stream().toList());
        assertEquals(List.of(1, 2), q.stream().toList(), "original unchanged");
    }

    @Test
    void oldVersionCanBeDequeuedRepeatedly() {
        PersistentQueue<Integer> q = PersistentQueue.<Integer>empty().enqueue(1).enqueue(2);
        assertEquals(1, q.dequeue().head());
        assertEquals(1, q.dequeue().head());
        assertEquals(2, q.size());
    }

    @Test
    void equalsByElementSequenceRegardlessOfInternalSplit() {
        PersistentQueue<Integer> a = PersistentQueue.<Integer>empty().enqueue(1).enqueue(2).enqueue(3);
        PersistentQueue<Integer> b = PersistentQueue.<Integer>empty().enqueue(0).enqueue(1).enqueue(2)
                .dequeue().rest().enqueue(3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, b.enqueue(4));
    }

    @Test
    void largeQueueDrainsInOrder() {
        PersistentQueue<Integer> q = PersistentQueue.empty();
        for (int i = 0; i < 100_000; i++) q = q.enqueue(i);
        List<Integer> out = new ArrayList<>();
        while (!q.isEmpty()) {
            var d = q.dequeue();
            out.add(d.head());
            q = d.rest();
        }
        assertEquals(100_000, out.size());
        for (int i = 0; i < out.size(); i++) assertEquals(i, out.get(i));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> PersistentQueue.<String>empty().enqueue(null));
    }
}
