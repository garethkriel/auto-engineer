package ae.raft;

/** Outgoing side of a node: fire-and-forget, may drop, delay or duplicate. */
@FunctionalInterface
public interface Transport {
    void send(int to, Message message);
}
