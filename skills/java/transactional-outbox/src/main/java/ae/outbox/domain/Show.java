package ae.outbox.domain;

/** A show with a fixed capacity and a count of reserved seats. Immutable. */
public record Show(String id, int capacity, int reserved) {
    public Show {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (capacity < 0 || reserved < 0 || reserved > capacity) {
            throw new IllegalArgumentException("invalid capacity/reserved: " + capacity + "/" + reserved);
        }
    }

    public static Show of(String id, int capacity) {
        return new Show(id, capacity, 0);
    }

    public int free() {
        return capacity - reserved;
    }

    public Show reserve(int seats) {
        requirePositive(seats);
        if (seats > free()) {
            throw new SoldOutException("show " + id + " has " + free() + " free seats, requested " + seats);
        }
        return new Show(id, capacity, reserved + seats);
    }

    public Show release(int seats) {
        requirePositive(seats);
        if (seats > reserved) {
            throw new IllegalStateException("cannot release " + seats + " of " + reserved + " reserved");
        }
        return new Show(id, capacity, reserved - seats);
    }

    private static void requirePositive(int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive: " + seats);
        }
    }
}
