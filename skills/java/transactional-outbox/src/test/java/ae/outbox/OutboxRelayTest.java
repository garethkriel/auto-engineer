package ae.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.outbox.adapter.InMemoryDatabase;
import ae.outbox.app.BookingService;
import ae.outbox.app.IdempotentReceiver;
import ae.outbox.app.OutboxRelay;
import ae.outbox.app.RelayReport;
import ae.outbox.domain.OutboxMessage;
import ae.outbox.domain.OutboxMessage.Status;
import ae.outbox.port.MessageSink;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {
    private final InMemoryDatabase db = new InMemoryDatabase();
    private final TestClock clock = new TestClock();
    private final BookingService svc = new BookingService(db, clock);
    /** Payloads whose delivery should fail, with remaining failure counts. */
    private final Set<String> failing = new HashSet<>();
    private final List<String> delivered = new ArrayList<>();
    private final MessageSink sink = m -> {
        if (failing.contains(m.payload())) {
            throw new IllegalStateException("down: " + m.payload());
        }
        delivered.add(m.payload());
    };
    // base 10s, cap 25s, 3 attempts
    private final OutboxRelay relay = new OutboxRelay(db, sink, clock, 3, Duration.ofSeconds(10), Duration.ofSeconds(25));

    @BeforeEach
    void shows() {
        svc.createShow("A", 100);
        svc.createShow("B", 100);
    }

    private OutboxMessage message(long id) {
        return db.inTransaction(tx -> tx.allMessages().stream().filter(m -> m.id() == id).findFirst().orElseThrow());
    }

    @Test
    void deliversInIdOrderAndMarksDelivered() {
        svc.reserve("A", "ann", 1);
        svc.reserve("A", "bob", 2);
        RelayReport r = relay.runOnce();
        assertEquals(new RelayReport(2, 0, 0), r);
        assertEquals(List.of("booking.reserved|b1|A|ann|1", "booking.reserved|b2|A|bob|2"), delivered);
        assertEquals(new RelayReport(0, 0, 0), relay.runOnce(), "nothing left to do");
        assertEquals(2, delivered.size());
    }

    @Test
    void failureBacksOffExponentiallyWithCapAndIsNotRetriedEarly() {
        svc.reserve("A", "ann", 1);
        failing.add("booking.reserved|b1|A|ann|1");
        assertEquals(new RelayReport(0, 1, 0), relay.runOnce());
        OutboxMessage m = message(1);
        assertEquals(1, m.attempts());
        assertEquals(TestClock.START.plusSeconds(10), m.nextAttemptAt());
        assertEquals("down: booking.reserved|b1|A|ann|1", m.lastError());

        clock.advance(Duration.ofSeconds(9));
        assertEquals(new RelayReport(0, 0, 0), relay.runOnce(), "not due yet");
        assertEquals(1, message(1).attempts());

        clock.advance(Duration.ofSeconds(1)); // t = 10s
        assertEquals(new RelayReport(0, 1, 0), relay.runOnce());
        // second failure: 10 * 2 = 20s after t=10s
        assertEquals(TestClock.START.plusSeconds(30), message(1).nextAttemptAt());
    }

    @Test
    void thirdFailureDeadLettersAndCapApplies() {
        svc.reserve("A", "ann", 1);
        failing.add("booking.reserved|b1|A|ann|1");
        relay.runOnce();                       // attempt 1 at t=0, next t=10
        clock.advance(Duration.ofSeconds(10));
        relay.runOnce();                       // attempt 2 at t=10, next t=30
        clock.advance(Duration.ofSeconds(20));
        assertEquals(new RelayReport(0, 0, 1), relay.runOnce()); // attempt 3 -> dead
        OutboxMessage m = message(1);
        assertEquals(Status.DEAD, m.status());
        assertEquals(3, m.attempts());
        clock.advance(Duration.ofDays(1));
        assertEquals(new RelayReport(0, 0, 0), relay.runOnce(), "dead messages are never retried");
        assertTrue(delivered.isEmpty());
    }

    @Test
    void backoffCapsAtMaxDelay() {
        OutboxRelay r = new OutboxRelay(db, sink, clock, 100, Duration.ofSeconds(10), Duration.ofSeconds(25));
        // 10, 20, then capped at 25 forever (attempt 70 would overflow a naive shift)
        assertEquals(Duration.ofSeconds(10), backoff(r, 1));
        assertEquals(Duration.ofSeconds(20), backoff(r, 2));
        assertEquals(Duration.ofSeconds(25), backoff(r, 3));
        assertEquals(Duration.ofSeconds(25), backoff(r, 70));
        assertEquals(Duration.ofSeconds(25), backoff(r, Integer.MAX_VALUE));
    }

    private static Duration backoff(OutboxRelay r, int attempts) {
        // package-private in ae.outbox.app, so measure it through behaviour is not possible here; use reflection-free
        // approach via a helper in the same package
        return ae.outbox.app.RelayProbe.backoff(r, attempts);
    }

    @Test
    void failingHeadBlocksLaterMessagesOfSameKeyButNotOtherKeys() {
        svc.reserve("A", "ann", 1); // id1 key A (fails)
        svc.reserve("A", "bob", 1); // id2 key A (must wait)
        svc.reserve("B", "cy", 1);  // id3 key B (flows)
        failing.add("booking.reserved|b1|A|ann|1");
        assertEquals(new RelayReport(1, 1, 0), relay.runOnce());
        assertEquals(List.of("booking.reserved|b3|B|cy|1"), delivered);

        failing.clear();
        clock.advance(Duration.ofSeconds(10));
        assertEquals(new RelayReport(2, 0, 0), relay.runOnce());
        assertEquals(List.of("booking.reserved|b3|B|cy|1", "booking.reserved|b1|A|ann|1",
                "booking.reserved|b2|A|bob|1"), delivered);
    }

    @Test
    void deadHeadNoLongerBlocksItsKey() {
        svc.reserve("A", "ann", 1);
        svc.reserve("A", "bob", 1);
        failing.add("booking.reserved|b1|A|ann|1");
        relay.runOnce();
        clock.advance(Duration.ofSeconds(10));
        relay.runOnce();
        clock.advance(Duration.ofSeconds(20));
        assertEquals(new RelayReport(1, 0, 1), relay.runOnce(), "head dies, follower delivered in the same run");
        assertEquals(List.of("booking.reserved|b2|A|bob|1"), delivered);
    }

    @Test
    void interruptedSinkRestoresFlagAndRecordsNothing() {
        svc.reserve("A", "ann", 1);
        OutboxRelay r = new OutboxRelay(db, m -> {
            throw new InterruptedException("stop");
        }, clock, 3, Duration.ofSeconds(10), Duration.ofSeconds(25));
        try {
            assertEquals(new RelayReport(0, 0, 0), r.runOnce());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, message(1).attempts());
        } finally {
            Thread.interrupted(); // clear flag so later tests are unaffected
        }
    }

    @Test
    void lostAckRedeliversAndIdempotentReceiverProcessesOnce() {
        svc.reserve("A", "ann", 1);
        List<String> processed = new ArrayList<>();
        IdempotentReceiver receiver = new IdempotentReceiver(m -> processed.add(m.payload()));
        boolean[] dropAck = {true};
        MessageSink lossy = m -> {
            receiver.deliver(m);
            if (dropAck[0]) {
                dropAck[0] = false;
                throw new java.io.IOException("ack lost");
            }
        };
        OutboxRelay r = new OutboxRelay(db, lossy, clock, 3, Duration.ofSeconds(10), Duration.ofSeconds(25));
        assertEquals(new RelayReport(0, 1, 0), r.runOnce());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(new RelayReport(1, 0, 0), r.runOnce());
        assertEquals(List.of("booking.reserved|b1|A|ann|1"), processed, "delivered twice, processed once");
        assertEquals(Status.DELIVERED, message(1).status());
    }

    @Test
    void receiverRetriesAfterHandlerFailure() {
        int[] calls = {0};
        IdempotentReceiver rec = new IdempotentReceiver(m -> {
            if (++calls[0] == 1) {
                throw new IllegalStateException("handler down");
            }
        });
        OutboxMessage m = OutboxMessage.created(7, "k", "t", "p", Instant.EPOCH);
        assertThrows(IllegalStateException.class, () -> rec.deliver(m));
        rec.deliver(m);
        rec.deliver(m);
        assertEquals(2, calls[0]);
    }

    @Test
    void invalidPolicyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelay(db, sink, clock, 0, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelay(db, sink, clock, 1, Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelay(db, sink, clock, 1, Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }
}
