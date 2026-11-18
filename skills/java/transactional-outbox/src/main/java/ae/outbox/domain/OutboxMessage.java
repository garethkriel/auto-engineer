package ae.outbox.domain;

import java.time.Instant;

/** One row of the transactional outbox. Immutable; the {@code with*} methods return updated copies. */
public record OutboxMessage(long id, String key, String topic, String payload, int attempts,
                            Instant nextAttemptAt, Status status, String lastError) {

    /** Lifecycle of a message. */
    public enum Status { PENDING, DELIVERED, DEAD }

    public static OutboxMessage created(long id, String key, String topic, String payload, Instant now) {
        return new OutboxMessage(id, key, topic, payload, 0, now, Status.PENDING, null);
    }

    public OutboxMessage delivered() {
        return new OutboxMessage(id, key, topic, payload, attempts, nextAttemptAt, Status.DELIVERED, lastError);
    }

    public OutboxMessage failed(Instant next, String error, boolean dead) {
        return new OutboxMessage(id, key, topic, payload, attempts + 1, next,
                dead ? Status.DEAD : Status.PENDING, error);
    }
}
