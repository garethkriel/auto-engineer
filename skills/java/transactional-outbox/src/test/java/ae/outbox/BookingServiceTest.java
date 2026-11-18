package ae.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.outbox.adapter.InMemoryDatabase;
import ae.outbox.app.BookingService;
import ae.outbox.domain.OutboxMessage;
import ae.outbox.domain.SoldOutException;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class BookingServiceTest {
    private final InMemoryDatabase db = new InMemoryDatabase();
    private final TestClock clock = new TestClock();
    private final BookingService svc = new BookingService(db, clock);

    private List<OutboxMessage> messages() {
        return db.inTransaction(tx -> tx.allMessages());
    }

    private int reserved(String show) {
        return db.inTransaction(tx -> tx.findShow(show).orElseThrow().reserved());
    }

    @Test
    void reserveWritesBookingSeatsAndOneMessage() {
        svc.createShow("s1", 10);
        String id = svc.reserve("s1", "ann", 3);
        assertEquals("b1", id);
        assertEquals(3, reserved("s1"));
        List<OutboxMessage> ms = messages();
        assertEquals(1, ms.size());
        assertEquals("booking.reserved|b1|s1|ann|3", ms.get(0).payload());
        assertEquals("s1", ms.get(0).key());
        assertEquals(OutboxMessage.Status.PENDING, ms.get(0).status());
        assertEquals(TestClock.START, ms.get(0).nextAttemptAt());
    }

    @Test
    void soldOutLeavesNoBookingNoMessageAndNoBurnedId() {
        svc.createShow("s1", 2);
        assertThrows(SoldOutException.class, () -> svc.reserve("s1", "ann", 3));
        assertEquals(0, reserved("s1"));
        assertTrue(messages().isEmpty());
        assertEquals("b1", svc.reserve("s1", "bob", 2), "failed attempt must not consume booking number");
    }

    @Test
    void cancelReleasesSeatsAndEnqueuesCancelledMessage() {
        svc.createShow("s1", 10);
        String id = svc.reserve("s1", "ann", 4);
        svc.cancel(id);
        assertEquals(0, reserved("s1"));
        List<String> payloads = messages().stream().map(OutboxMessage::payload).toList();
        assertEquals(List.of("booking.reserved|b1|s1|ann|4", "booking.cancelled|b1|s1|ann|4"), payloads);
    }

    @Test
    void cancelTwiceFailsWithoutExtraMessage() {
        svc.createShow("s1", 10);
        String id = svc.reserve("s1", "ann", 4);
        svc.cancel(id);
        assertThrows(IllegalStateException.class, () -> svc.cancel(id));
        assertEquals(2, messages().size());
        assertEquals(0, reserved("s1"));
    }

    @Test
    void validationErrors() {
        svc.createShow("s1", 10);
        assertThrows(NoSuchElementException.class, () -> svc.reserve("nope", "ann", 1));
        assertThrows(NoSuchElementException.class, () -> svc.cancel("b9"));
        assertThrows(IllegalArgumentException.class, () -> svc.reserve("s1", "a|b", 1));
        assertThrows(IllegalArgumentException.class, () -> svc.reserve("s1", " ", 1));
        assertThrows(IllegalArgumentException.class, () -> svc.reserve("s1", "ann", 0));
        assertThrows(IllegalStateException.class, () -> svc.createShow("s1", 5));
        assertTrue(messages().isEmpty());
    }
}
