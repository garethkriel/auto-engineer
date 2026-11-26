package ae.qos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/** Strict priority: smaller number first, FIFO (enqueue order) among equal priorities. */
public final class PriorityScheduler implements Scheduler {
    private record Entry(int priority, long seq, Packet packet) {}

    private final Map<String, Integer> priorities;
    private final PriorityQueue<Entry> heap = new PriorityQueue<>(
            (a, b) -> a.priority != b.priority ? Integer.compare(a.priority, b.priority) : Long.compare(a.seq, b.seq));
    private long seq;
    private long backlog;

    public PriorityScheduler(Map<String, Integer> priorities) {
        this.priorities = new HashMap<>(priorities);
    }

    @Override
    public void enqueue(Packet p) {
        Integer prio = priorities.get(p.flow());
        if (prio == null) throw new IllegalArgumentException("unknown flow: " + p.flow());
        heap.add(new Entry(prio, seq++, p));
        backlog += p.size();
    }

    @Override
    public Optional<Packet> dequeue() {
        Entry e = heap.poll();
        if (e == null) return Optional.empty();
        backlog -= e.packet.size();
        return Optional.of(e.packet);
    }

    @Override
    public long backlogBytes() {
        return backlog;
    }
}
