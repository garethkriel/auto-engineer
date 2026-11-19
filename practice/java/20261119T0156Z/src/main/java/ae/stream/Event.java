package ae.stream;

import java.util.Objects;

/** One input record: a partition key, the event time and a numeric value. */
public record Event(String key, long time, long value) {
    public Event {
        Objects.requireNonNull(key, "key");
    }
}
