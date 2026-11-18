package ae.outbox.app;

import ae.outbox.domain.OutboxMessage;
import ae.outbox.port.Database;
import ae.outbox.port.MessageSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;

/** Publishes pending outbox messages at-least-once, in id order per key, with exponential backoff and dead-lettering. */
public final class OutboxRelay {
    private final Database db;
    private final MessageSink sink;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public OutboxRelay(Database db, MessageSink sink, Clock clock, int maxAttempts,
                       Duration baseDelay, Duration maxDelay) {
        if (maxAttempts < 1 || baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("invalid retry policy");
        }
        this.db = Objects.requireNonNull(db);
        this.sink = Objects.requireNonNull(sink);
        this.clock = Objects.requireNonNull(clock);
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    /** Delay before retry number {@code attempts} (1-based): base * 2^(attempts-1), capped at the maximum. */
    Duration backoff(int attempts) {
        long millis = baseDelay.toMillis();
        long cap = maxDelay.toMillis();
        for (int i = 1; i < attempts && millis < cap; i++) {
            millis = Math.min(cap, millis * 2);
        }
        return Duration.ofMillis(Math.min(millis, cap));
    }

    public RelayReport runOnce() {
        Instant now = clock.instant();
        List<OutboxMessage> pending = db.inTransaction(tx -> tx.pending());
        Map<String, List<OutboxMessage>> byKey = new LinkedHashMap<>();
        for (OutboxMessage m : pending) {
            byKey.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(m);
        }
        int delivered = 0;
        int retried = 0;
        int dead = 0;
        for (List<OutboxMessage> queue : byKey.values()) {
            for (OutboxMessage m : queue) {
                if (m.nextAttemptAt().isAfter(now)) {
                    break; // head not due: everything behind it waits to keep per-key order
                }
                try {
                    sink.deliver(m);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new RelayReport(delivered, retried, dead);
                } catch (Exception e) {
                    boolean isDead = m.attempts() + 1 >= maxAttempts;
                    OutboxMessage failed = m.failed(now.plus(backoff(m.attempts() + 1)),
                            String.valueOf(e.getMessage()), isDead);
                    db.inTransaction(tx -> {
                        tx.update(failed);
                        return null;
                    });
                    if (isDead) {
                        dead++;
                        continue; // a dead message no longer blocks its key
                    }
                    retried++;
                    break;
                }
                OutboxMessage done = m.delivered();
                db.inTransaction(tx -> {
                    tx.update(done);
                    return null;
                });
                delivered++;
            }
        }
        return new RelayReport(delivered, retried, dead);
    }
}
