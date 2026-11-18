package ae.outbox.domain;

/** A booking of seats on a show by a customer. Immutable. */
public record Booking(String id, String showId, String customer, int seats, boolean cancelled) {
    public Booking {
        if (id == null || showId == null || customer == null) {
            throw new IllegalArgumentException("null field");
        }
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive");
        }
    }

    public Booking cancel() {
        if (cancelled) {
            throw new IllegalStateException("booking " + id + " already cancelled");
        }
        return new Booking(id, showId, customer, seats, true);
    }
}
