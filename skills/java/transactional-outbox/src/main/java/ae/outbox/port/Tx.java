package ae.outbox.port;

import ae.outbox.domain.Booking;
import ae.outbox.domain.OutboxMessage;
import ae.outbox.domain.Show;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Operations available inside one database transaction. Valid only while the transaction runs. */
public interface Tx {
    Optional<Show> findShow(String id);

    void saveShow(Show show);

    Optional<Booking> findBooking(String id);

    void saveBooking(Booking booking);

    /** Next booking number, 1-based, part of the transaction (rolled back with it). */
    long nextBookingNumber();

    OutboxMessage enqueue(String key, String topic, String payload, Instant now);

    /** All PENDING messages ordered by id. */
    List<OutboxMessage> pending();

    /** Every message ordered by id. */
    List<OutboxMessage> allMessages();

    /** Replaces the stored message with the same id. */
    void update(OutboxMessage message);
}
