package ae.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.outbox.adapter.InMemoryDatabase;
import ae.outbox.domain.Show;
import ae.outbox.port.Tx;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseTest {
    private final InMemoryDatabase db = new InMemoryDatabase();

    @Test
    void exceptionRollsBackShowSequenceAndOutbox() {
        db.inTransaction(tx -> {
            tx.saveShow(Show.of("s", 5));
            return null;
        });
        assertThrows(IllegalStateException.class, () -> db.inTransaction(tx -> {
            tx.saveShow(Show.of("s", 5).reserve(2));
            tx.nextBookingNumber();
            tx.enqueue("s", "t", "p", TestClock.START);
            throw new IllegalStateException("boom");
        }));
        db.inTransaction(tx -> {
            assertEquals(0, tx.findShow("s").orElseThrow().reserved());
            assertTrue(tx.allMessages().isEmpty());
            assertEquals(1, tx.nextBookingNumber(), "sequence rolled back too");
            return null;
        });
    }

    @Test
    void commitMakesWritesVisibleAndIdsAscend() {
        List<Long> ids = db.inTransaction(tx -> {
            List<Long> out = new ArrayList<>();
            out.add(tx.enqueue("k", "t", "a", TestClock.START).id());
            out.add(tx.enqueue("k", "t", "b", TestClock.START).id());
            return out;
        });
        assertEquals(List.of(1L, 2L), ids);
        assertEquals(2, (int) db.inTransaction(tx -> tx.pending().size()));
    }

    @Test
    void nestedTransactionRejected() {
        assertThrows(IllegalStateException.class,
                () -> db.inTransaction(tx -> db.inTransaction(inner -> null)));
    }

    @Test
    void leakedTxIsDeadAfterCommit() {
        Tx leaked = db.inTransaction(tx -> tx);
        assertThrows(IllegalStateException.class, () -> leaked.findShow("s"));
    }

    @Test
    void updateOfUnknownMessageRejected() {
        assertThrows(IllegalArgumentException.class, () -> db.inTransaction(tx -> {
            tx.update(ae.outbox.domain.OutboxMessage.created(99, "k", "t", "p", TestClock.START));
            return null;
        }));
    }
}
