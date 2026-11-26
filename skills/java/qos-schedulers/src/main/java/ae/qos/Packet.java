package ae.qos;

import java.util.Objects;

/** A packet of {@code size} bytes of one flow, arriving at tick {@code arrival}. */
public record Packet(int id, String flow, int size, long arrival) {
    public Packet {
        Objects.requireNonNull(flow, "flow");
        if (flow.isBlank()) throw new IllegalArgumentException("flow must not be blank");
        if (size <= 0) throw new IllegalArgumentException("size must be positive: " + size);
        if (arrival < 0) throw new IllegalArgumentException("arrival must be >= 0: " + arrival);
    }
}
