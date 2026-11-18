package ae.outbox.app;

import ae.outbox.domain.Booking;
import ae.outbox.domain.Show;
import ae.outbox.port.Database;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Command handlers. Each writes the business change and its outbox message in one transaction. */
public final class BookingService {
    private final Database db;
    private final Clock clock;

    public BookingService(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    public void createShow(String showId, int capacity) {
        db.inTransaction(tx -> {
            if (tx.findShow(showId).isPresent()) {
                throw new IllegalStateException("show exists: " + showId);
            }
            tx.saveShow(Show.of(showId, capacity));
            return null;
        });
    }

    /** Reserves seats; returns the new booking id. */
    public String reserve(String showId, String customer, int seats) {
        Objects.requireNonNull(showId);
        if (customer == null || customer.isBlank() || customer.indexOf('|') >= 0) {
            throw new IllegalArgumentException("invalid customer: " + customer);
        }
        return db.inTransaction(tx -> {
            Show show = tx.findShow(showId).orElseThrow(() -> new NoSuchElementException("show " + showId));
            Show updated = show.reserve(seats);
            String id = "b" + tx.nextBookingNumber();
            tx.saveShow(updated);
            tx.saveBooking(new Booking(id, showId, customer, seats, false));
            tx.enqueue(showId, "booking.reserved", payload("booking.reserved", id, showId, customer, seats),
                    clock.instant());
            return id;
        });
    }

    public void cancel(String bookingId) {
        db.inTransaction(tx -> {
            Booking b = tx.findBooking(bookingId)
                    .orElseThrow(() -> new NoSuchElementException("booking " + bookingId));
            Booking cancelled = b.cancel();
            Show show = tx.findShow(b.showId()).orElseThrow(() -> new IllegalStateException("show vanished"));
            tx.saveShow(show.release(b.seats()));
            tx.saveBooking(cancelled);
            tx.enqueue(b.showId(), "booking.cancelled",
                    payload("booking.cancelled", b.id(), b.showId(), b.customer(), b.seats()), clock.instant());
            return null;
        });
    }

    private static String payload(String topic, String id, String showId, String customer, int seats) {
        return topic + "|" + id + "|" + showId + "|" + customer + "|" + seats;
    }
}
