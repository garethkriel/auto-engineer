package ae.es.port;

import ae.es.domain.CartEvent;
import java.util.List;

/** Port: append-only event storage with optimistic concurrency. */
public interface EventStore {
    /**
     * Atomically appends {@code events} to {@code streamId} if the stream currently holds exactly
     * {@code expectedVersion} events; otherwise throws {@link ConcurrencyException} and stores nothing.
     * A batch containing null is rejected with {@link NullPointerException} and stores nothing.
     */
    void append(String streamId, long expectedVersion, List<? extends CartEvent> events);

    /** Snapshot of one stream in version order (empty if unknown). */
    List<StoredEvent> read(String streamId);

    /** Snapshot of every event with position greater than {@code afterPosition}, in position order. */
    List<StoredEvent> readAll(long afterPosition);
}
