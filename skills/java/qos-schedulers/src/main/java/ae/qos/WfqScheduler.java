package ae.qos;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Self-clocked fair queueing with exact integer tags. Tag unit = bytes * (scale / weight) where scale is the
 * lcm of all weights, so every increment is an integer.
 */
public final class WfqScheduler implements Scheduler {
    private record Entry(long tag, long seq, Packet packet) {}

    private final Map<String, Integer> weights;
    private final long scale;
    private final Map<String, Long> lastFinish = new HashMap<>();
    private final PriorityQueue<Entry> heap = new PriorityQueue<>(
            (a, b) -> a.tag != b.tag ? Long.compare(a.tag, b.tag) : Long.compare(a.seq, b.seq));
    private long virtualTime;
    private long seq;
    private long backlog;

    public WfqScheduler(Map<String, Integer> weights) {
        if (weights.isEmpty()) throw new IllegalArgumentException("no flows");
        BigInteger lcm = BigInteger.ONE;
        for (Map.Entry<String, Integer> e : weights.entrySet()) {
            if (e.getValue() < 1) throw new IllegalArgumentException("weight must be >= 1 for " + e.getKey());
            BigInteger w = BigInteger.valueOf(e.getValue());
            lcm = lcm.divide(lcm.gcd(w)).multiply(w);
        }
        this.scale = lcm.longValueExact(); // ArithmeticException when the lcm does not fit a long
        this.weights = new HashMap<>(weights);
    }

    @Override
    public void enqueue(Packet p) {
        Integer w = weights.get(p.flow());
        if (w == null) throw new IllegalArgumentException("unknown flow: " + p.flow());
        long start = Math.max(lastFinish.getOrDefault(p.flow(), 0L), virtualTime);
        long finish = Math.addExact(start, Math.multiplyExact((long) p.size(), scale / w)); // may throw: no state touched yet
        lastFinish.put(p.flow(), finish);
        heap.add(new Entry(finish, seq++, p));
        backlog += p.size();
    }

    @Override
    public Optional<Packet> dequeue() {
        Entry e = heap.poll();
        if (e == null) return Optional.empty();
        virtualTime = e.tag;
        backlog -= e.packet.size();
        return Optional.of(e.packet);
    }

    @Override
    public long backlogBytes() {
        return backlog;
    }
}
