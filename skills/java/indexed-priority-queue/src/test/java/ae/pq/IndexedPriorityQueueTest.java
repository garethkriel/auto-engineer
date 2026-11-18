package ae.pq;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IndexedPriorityQueueTest {

    private static IndexedPriorityQueue<Integer> q(int cap, int arity) {
        return new IndexedPriorityQueue<>(cap, Comparator.naturalOrder(), arity);
    }

    @Test
    void pollsInKeyOrder() {
        IndexedPriorityQueue<Integer> pq = q(5, 2);
        pq.insert(0, 50);
        pq.insert(1, 10);
        pq.insert(2, 40);
        pq.insert(3, 20);
        pq.insert(4, 30);
        List<Integer> ids = new ArrayList<>();
        while (!pq.isEmpty()) {
            ids.add(pq.pollId());
        }
        assertEquals(List.of(1, 3, 4, 2, 0), ids);
    }

    @Test
    void tiesBreakBySmallerId() {
        IndexedPriorityQueue<Integer> pq = q(4, 3);
        pq.insert(3, 7);
        pq.insert(1, 7);
        pq.insert(2, 7);
        pq.insert(0, 7);
        assertEquals(0, pq.pollId());
        assertEquals(1, pq.pollId());
        assertEquals(2, pq.pollId());
        assertEquals(3, pq.pollId());
    }

    @Test
    void changeKeyLowerMovesToFront() {
        IndexedPriorityQueue<Integer> pq = q(3, 2);
        pq.insert(0, 10);
        pq.insert(1, 20);
        pq.insert(2, 30);
        pq.changeKey(2, 5);
        assertEquals(2, pq.peekId());
        assertEquals(5, pq.peekKey());
        assertEquals(5, pq.keyOf(2));
    }

    @Test
    void changeKeyRaiseMovesBack() {
        IndexedPriorityQueue<Integer> pq = q(3, 2);
        pq.insert(0, 10);
        pq.insert(1, 20);
        pq.insert(2, 30);
        pq.changeKey(0, 100);
        assertEquals(1, pq.pollId());
        assertEquals(2, pq.pollId());
        assertEquals(0, pq.pollId());
    }

    @Test
    void removeMiddleReturnsKeyAndKeepsOrder() {
        IndexedPriorityQueue<Integer> pq = q(6, 2);
        for (int i = 0; i < 6; i++) {
            pq.insert(i, (i * 7) % 6 + 10); // keys 10,11,12,13,14,15 for ids 0,... permuted
        }
        int key = pq.remove(3);
        assertEquals((3 * 7) % 6 + 10, key);
        assertFalse(pq.contains(3));
        assertEquals(5, pq.size());
        int prev = Integer.MIN_VALUE;
        while (!pq.isEmpty()) {
            int k = pq.peekKey();
            assertTrue(k >= prev);
            prev = k;
            pq.pollId();
        }
    }

    @Test
    void idCanBeReinsertedAfterPollAndRemove() {
        IndexedPriorityQueue<String> pq = new IndexedPriorityQueue<>(2, Comparator.naturalOrder(), 4);
        pq.insert(0, "b");
        assertEquals(0, pq.pollId());
        assertFalse(pq.contains(0));
        pq.insert(0, "a");
        assertEquals("a", pq.remove(0));
        pq.insert(0, "c");
        assertEquals("c", pq.keyOf(0));
    }

    @Test
    void errorsAreReportedAndLeaveQueueUnchanged() {
        IndexedPriorityQueue<Integer> pq = q(2, 2);
        pq.insert(0, 1);
        assertThrows(IllegalStateException.class, () -> pq.insert(0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> pq.insert(2, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> pq.insert(-1, 2));
        assertThrows(NullPointerException.class, () -> pq.insert(1, null));
        assertThrows(NoSuchElementException.class, () -> pq.keyOf(1));
        assertThrows(NoSuchElementException.class, () -> pq.changeKey(1, 3));
        assertThrows(NoSuchElementException.class, () -> pq.remove(1));
        assertThrows(NullPointerException.class, () -> pq.changeKey(0, null));
        assertFalse(pq.contains(5));
        assertFalse(pq.contains(-1));
        assertEquals(1, pq.size());
        assertEquals(1, pq.keyOf(0));
    }

    @Test
    void emptyQueueThrows() {
        IndexedPriorityQueue<Integer> pq = q(0, 2);
        assertTrue(pq.isEmpty());
        assertThrows(NoSuchElementException.class, pq::peekId);
        assertThrows(NoSuchElementException.class, pq::peekKey);
        assertThrows(NoSuchElementException.class, pq::pollId);
    }

    @Test
    void constructorValidatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> q(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> q(3, 1));
        assertThrows(IllegalArgumentException.class, () -> q(3, 17));
        assertThrows(NullPointerException.class, () -> new IndexedPriorityQueue<Integer>(3, null, 2));
    }

    @Test
    void clearEmptiesAndAllowsReuse() {
        IndexedPriorityQueue<Integer> pq = q(3, 2);
        pq.insert(0, 3);
        pq.insert(2, 1);
        pq.clear();
        assertEquals(0, pq.size());
        assertFalse(pq.contains(0));
        assertFalse(pq.contains(2));
        pq.insert(2, 9);
        assertEquals(2, pq.peekId());
    }

    @Test
    void honoursCustomComparatorForMaxHeap() {
        IndexedPriorityQueue<Integer> pq = new IndexedPriorityQueue<>(3, Comparator.reverseOrder(), 2);
        pq.insert(0, 1);
        pq.insert(1, 9);
        pq.insert(2, 5);
        assertEquals(1, pq.pollId());
        assertEquals(2, pq.pollId());
    }

    /** Model entry ordering: key, then id. */
    private record E(int key, int id) {}

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 8, 16})
    void randomOperationsMatchTreeSetModel(int arity) {
        for (long seed = 1; seed <= 20; seed++) {
            Random rnd = new Random(seed * 31 + arity);
            int cap = 40;
            IndexedPriorityQueue<Integer> pq = q(cap, arity);
            TreeSet<E> model = new TreeSet<>(Comparator.comparingInt(E::key).thenComparingInt(E::id));
            Integer[] keys = new Integer[cap];
            for (int step = 0; step < 600; step++) {
                int id = rnd.nextInt(cap);
                int op = rnd.nextInt(5);
                String ctx = "arity=" + arity + " seed=" + seed + " step=" + step + " op=" + op + " id=" + id;
                if (op == 0 || op == 1) {
                    int key = rnd.nextInt(15); // small range forces many ties
                    if (keys[id] == null) {
                        pq.insert(id, key);
                        keys[id] = key;
                        model.add(new E(key, id));
                    } else {
                        pq.changeKey(id, key);
                        model.remove(new E(keys[id], id));
                        keys[id] = key;
                        model.add(new E(key, id));
                    }
                } else if (op == 2) {
                    if (keys[id] != null) {
                        assertEquals(keys[id], pq.remove(id), ctx);
                        model.remove(new E(keys[id], id));
                        keys[id] = null;
                    }
                } else if (op == 3) {
                    if (!model.isEmpty()) {
                        E first = model.pollFirst();
                        assertEquals(first.id(), pq.pollId(), ctx);
                        keys[first.id()] = null;
                    }
                } else {
                    assertEquals(keys[id] != null, pq.contains(id), ctx);
                }
                assertEquals(model.size(), pq.size(), ctx);
                if (!model.isEmpty()) {
                    assertEquals(model.first().id(), pq.peekId(), ctx);
                    assertEquals(model.first().key(), pq.peekKey(), ctx);
                }
            }
        }
    }

    @Test
    void largeHeapSortsCorrectly() {
        int n = 20_000;
        IndexedPriorityQueue<Integer> pq = q(n, 4);
        Random rnd = new Random(7);
        int[] keys = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = rnd.nextInt(1_000_000);
            pq.insert(i, keys[i]);
        }
        int prevKey = Integer.MIN_VALUE;
        int prevId = -1;
        for (int i = 0; i < n; i++) {
            int k = pq.peekKey();
            int id = pq.pollId();
            assertEquals(keys[id], k);
            assertTrue(k > prevKey || (k == prevKey && id > prevId), "order broken at " + i);
            prevKey = k;
            prevId = id;
        }
    }
}
