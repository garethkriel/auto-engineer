package ae.es;

import static org.junit.jupiter.api.Assertions.*;

import ae.es.adapter.InMemoryEventStore;
import ae.es.domain.CartEvent;
import ae.es.port.ConcurrencyException;
import ae.es.port.EventStore;
import ae.es.port.StoredEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class EventStoreTest {

    private static final CartEvent OPEN = new CartEvent.CartOpened();
    private static CartEvent add(String s) {
        return new CartEvent.ItemAdded(s, 1, 10);
    }

    @Test
    void appendAssignsVersionsAndDensePositions() {
        EventStore s = new InMemoryEventStore();
        s.append("c1", 0, List.of(OPEN, add("a")));
        s.append("c2", 0, List.of(OPEN));
        s.append("c1", 2, List.of(add("b")));
        List<StoredEvent> c1 = s.read("c1");
        assertEquals(List.of(1L, 2L, 3L), c1.stream().map(StoredEvent::version).toList());
        assertEquals(List.of(1L, 2L, 4L), c1.stream().map(StoredEvent::position).toList());
        List<StoredEvent> all = s.readAll(0);
        assertEquals(List.of(1L, 2L, 3L, 4L), all.stream().map(StoredEvent::position).toList());
        assertEquals(List.of("c1", "c1", "c2", "c1"), all.stream().map(StoredEvent::streamId).toList());
    }

    @Test
    void staleExpectedVersionRejectedAndStoresNothing() {
        EventStore s = new InMemoryEventStore();
        s.append("c1", 0, List.of(OPEN));
        ConcurrencyException ex = assertThrows(ConcurrencyException.class,
                () -> s.append("c1", 0, List.of(add("a"), add("b"))));
        assertEquals(1, ex.actualVersion());
        assertEquals(1, s.read("c1").size());
        assertEquals(1, s.readAll(0).size());
    }

    @Test
    void expectedVersionAheadOfStreamIsRejected() {
        EventStore s = new InMemoryEventStore();
        assertThrows(ConcurrencyException.class, () -> s.append("new", 3, List.of(OPEN)));
        assertTrue(s.read("new").isEmpty());
    }

    @Test
    void emptyAppendIsNoOpButStillChecksVersion() {
        EventStore s = new InMemoryEventStore();
        s.append("c1", 0, List.of());
        assertTrue(s.readAll(0).isEmpty());
        assertThrows(ConcurrencyException.class, () -> s.append("c1", 5, List.of()));
    }

    @Test
    void nullEventInBatchRejectedWithoutPartialWrite() {
        EventStore s = new InMemoryEventStore();
        List<CartEvent> bad = new ArrayList<>();
        bad.add(OPEN);
        bad.add(null);
        assertThrows(NullPointerException.class, () -> s.append("c1", 0, bad));
        assertTrue(s.read("c1").isEmpty());
        assertTrue(s.readAll(0).isEmpty());
    }

    @Test
    void readAllFromPositionReturnsOnlyLaterEvents() {
        EventStore s = new InMemoryEventStore();
        s.append("c1", 0, List.of(OPEN, add("a"), add("b")));
        assertEquals(2, s.readAll(1).size());
        assertEquals(3L, s.readAll(1).get(1).position());
        assertTrue(s.readAll(3).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> s.readAll(-1));
    }

    @Test
    void returnedListsAreSnapshotsNotLiveViews() {
        EventStore s = new InMemoryEventStore();
        s.append("c1", 0, List.of(OPEN));
        List<StoredEvent> snap = s.read("c1");
        s.append("c1", 1, List.of(add("a")));
        assertEquals(1, snap.size());
        assertThrows(UnsupportedOperationException.class, () -> snap.add(snap.get(0)));
    }

    @Test
    void callerMutatingBatchAfterAppendDoesNotChangeStore() {
        EventStore s = new InMemoryEventStore();
        List<CartEvent> batch = new ArrayList<>(List.of(OPEN));
        s.append("c1", 0, batch);
        batch.add(add("x"));
        assertEquals(1, s.read("c1").size());
    }

    @Test
    void concurrentWritersToOneStreamExactlyOneWinsPerVersion() throws Exception {
        EventStore s = new InMemoryEventStore();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                try {
                    s.append("hot", 0, List.of(OPEN));
                    return true;
                } catch (ConcurrencyException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int wins = 0;
        for (Future<Boolean> f : fs) {
            if (f.get(10, TimeUnit.SECONDS)) wins++;
        }
        pool.shutdownNow();
        assertEquals(1, wins);
        assertEquals(1, s.readAll(0).size());
    }

    @Test
    void concurrentWritersToDifferentStreamsKeepPositionsDense() throws Exception {
        EventStore s = new InMemoryEventStore();
        int threads = 6;
        int per = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String id = "s" + t;
            fs.add(pool.submit(() -> {
                long v = 0;
                for (int i = 0; i < per; i++) {
                    s.append(id, v, List.of(add("x")));
                    v++;
                }
            }));
        }
        for (Future<?> f : fs) f.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();
        List<StoredEvent> all = s.readAll(0);
        assertEquals(threads * per, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1L, all.get(i).position());
        }
        for (int t = 0; t < threads; t++) {
            List<StoredEvent> st = s.read("s" + t);
            assertEquals(per, st.size());
            for (int i = 0; i < per; i++) assertEquals(i + 1L, st.get(i).version());
        }
    }
}
