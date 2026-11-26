package ae.qos;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Deficit round robin. The active list holds flows with queued packets in the order they became active; the
 * flow at its head is credited one quantum per turn and sends while the head packet fits in its deficit.
 */
public final class DrrScheduler implements Scheduler {
    private final Map<String, Integer> quanta;
    private final Map<String, ArrayDeque<Packet>> queues = new HashMap<>();
    private final Map<String, Long> deficit = new HashMap<>();
    private final ArrayDeque<String> active = new ArrayDeque<>();
    /** True once the flow at the head of {@code active} has been credited for its current turn. */
    private boolean headCredited;
    private long backlog;

    public DrrScheduler(Map<String, Integer> quanta) {
        if (quanta.isEmpty()) throw new IllegalArgumentException("no flows");
        for (Map.Entry<String, Integer> e : quanta.entrySet()) {
            if (e.getValue() < 1) throw new IllegalArgumentException("quantum must be >= 1 for " + e.getKey());
        }
        this.quanta = new HashMap<>(quanta);
    }

    @Override
    public void enqueue(Packet p) {
        if (!quanta.containsKey(p.flow())) throw new IllegalArgumentException("unknown flow: " + p.flow());
        ArrayDeque<Packet> q = queues.computeIfAbsent(p.flow(), k -> new ArrayDeque<>());
        if (q.isEmpty()) {
            active.addLast(p.flow());
            deficit.put(p.flow(), 0L);
        }
        q.addLast(p);
        backlog += p.size();
    }

    @Override
    public Optional<Packet> dequeue() {
        while (!active.isEmpty()) {
            String flow = active.peekFirst();
            if (!headCredited) {
                deficit.merge(flow, (long) quanta.get(flow), Long::sum);
                headCredited = true;
            }
            ArrayDeque<Packet> q = queues.get(flow);
            Packet head = q.peekFirst();
            if (head.size() <= deficit.get(flow)) {
                q.pollFirst();
                deficit.merge(flow, (long) -head.size(), Long::sum);
                backlog -= head.size();
                if (q.isEmpty()) {
                    deficit.put(flow, 0L);
                    active.pollFirst();
                    headCredited = false;
                }
                return Optional.of(head);
            }
            active.addLast(active.pollFirst());
            headCredited = false;
        }
        return Optional.empty();
    }

    @Override
    public long backlogBytes() {
        return backlog;
    }
}
