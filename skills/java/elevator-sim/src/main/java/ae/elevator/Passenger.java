package ae.elevator;

import java.util.Objects;

/** A passenger who appears on floor {@code from} at {@code arrivalTick} and wants to reach {@code to}. */
public record Passenger(String id, int arrivalTick, int from, int to) {
    public Passenger {
        Objects.requireNonNull(id, "id");
        if (arrivalTick < 0) throw new IllegalArgumentException("negative arrival tick");
        if (from == to) throw new IllegalArgumentException("from == to for " + id);
    }

    Direction direction() {
        return Direction.between(from, to);
    }
}
