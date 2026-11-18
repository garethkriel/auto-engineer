package ae.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.outbox.adapter.InMemoryDatabase;
import ae.outbox.app.BookingService;
import ae.outbox.app.IdempotentReceiver;
import ae.outbox.app.OutboxRelay;
import ae.outbox.domain.OutboxMessage;
import ae.outbox.domain.OutboxMessage.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SimulationTest {

    /**
     * Oracle: the payloads in the order the service published them, per show. With random sink failures and ack
     * losses (but enough attempts that nothing dies) the idempotent receiver must see exactly that sequence per key.
     */
    @Test
    void randomFailuresStillDeliverEverythingOncePerKeyInOrder() {
        for (long seed = 1; seed <= 40; seed++) {
            Random rnd = new Random(seed);
            InMemoryDatabase db = new InMemoryDatabase();
            TestClock clock = new TestClock();
            BookingService svc = new BookingService(db, clock);
            String[] shows = {"A", "B", "C"};
            for (String s : shows) {
                svc.createShow(s, 1000);
            }
            Map<String, List<String>> expected = new HashMap<>();
            Map<String, List<String>> received = new HashMap<>();
            IdempotentReceiver receiver = new IdempotentReceiver(
                    m -> received.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(m.payload()));
            Random failRnd = new Random(seed * 31);
            OutboxRelay relay = new OutboxRelay(db, m -> {
                int roll = failRnd.nextInt(10);
                if (roll < 3) {
                    throw new IllegalStateException("before delivery");
                }
                receiver.deliver(m);
                if (roll < 5) {
                    throw new IllegalStateException("ack lost");
                }
            }, clock, 1_000_000, Duration.ofSeconds(1), Duration.ofSeconds(8));

            List<String> bookings = new ArrayList<>();
            int ops = 30 + rnd.nextInt(30);
            for (int i = 0; i < ops; i++) {
                String show = shows[rnd.nextInt(shows.length)];
                if (!bookings.isEmpty() && rnd.nextInt(4) == 0) {
                    String id = bookings.remove(rnd.nextInt(bookings.size()));
                    svc.cancel(id);
                } else {
                    bookings.add(svc.reserve(show, "c" + rnd.nextInt(5), 1 + rnd.nextInt(3)));
                }
                if (rnd.nextInt(3) == 0) {
                    relay.runOnce();
                    clock.advance(Duration.ofSeconds(rnd.nextInt(5)));
                }
            }
            List<OutboxMessage> all = db.inTransaction(tx -> tx.allMessages());
            for (OutboxMessage m : all) {
                expected.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(m.payload());
            }
            for (int round = 0; round < 5000 && !db.inTransaction(tx -> tx.pending()).isEmpty(); round++) {
                relay.runOnce();
                clock.advance(Duration.ofSeconds(1));
            }
            assertTrue(db.inTransaction(tx -> tx.pending()).isEmpty(), "seed " + seed + " did not drain");
            assertEquals(expected, received, "seed " + seed);
            assertTrue(db.inTransaction(tx -> tx.allMessages()).stream().allMatch(m -> m.status() == Status.DELIVERED));
        }
    }

    @Test
    void concurrentReservationsNeverOverbook() throws Exception {
        InMemoryDatabase db = new InMemoryDatabase();
        BookingService svc = new BookingService(db, new TestClock());
        svc.createShow("S", 50);
        int threads = 8;
        int perThread = 10; // 80 single-seat attempts for 50 seats
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            fs.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    try {
                        svc.reserve("S", "t" + id, 1);
                        ok.incrementAndGet();
                    } catch (ae.outbox.domain.SoldOutException e) {
                        soldOut.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : fs) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(50, ok.get());
        assertEquals(30, soldOut.get());
        assertEquals(50, (int) db.inTransaction(tx -> tx.findShow("S").orElseThrow().reserved()));
        List<OutboxMessage> ms = db.inTransaction(tx -> tx.allMessages());
        assertEquals(50, ms.size());
        assertEquals(50, ms.stream().map(OutboxMessage::payload).distinct().count(), "booking ids are unique");
    }
}
