package ae.qos;

import java.util.ArrayDeque;
import java.util.Optional;

/** First come, first served across all flows. */
public final class FifoScheduler implements Scheduler {
    private final ArrayDeque<Packet> queue = new ArrayDeque<>();
    private long backlog;

    @Override
    public void enqueue(Packet p) {
        queue.addLast(p);
        backlog += p.size();
    }

    @Override
    public Optional<Packet> dequeue() {
        Packet p = queue.pollFirst();
        if (p == null) return Optional.empty();
        backlog -= p.size();
        return Optional.of(p);
    }

    @Override
    public long backlogBytes() {
        return backlog;
    }
}
