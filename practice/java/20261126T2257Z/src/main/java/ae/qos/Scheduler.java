package ae.qos;

import java.util.Optional;

/** Chooses which queued packet the link sends next. Implementations are not thread-safe. */
public interface Scheduler {
    /** Queues a packet; an unknown flow throws {@link IllegalArgumentException} and changes nothing. */
    void enqueue(Packet p);

    /** Removes and returns the next packet to send, or empty if nothing is queued. */
    Optional<Packet> dequeue();

    /** Total bytes currently queued. */
    long backlogBytes();

    default boolean isEmpty() {
        return backlogBytes() == 0;
    }
}
