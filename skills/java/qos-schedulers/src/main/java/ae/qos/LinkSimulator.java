package ae.qos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Discrete-time, non-preemptive single-link simulation. */
public final class LinkSimulator {
    private LinkSimulator() {}

    public record Departure(Packet packet, long start, long end) {}

    public record Result(List<Departure> departures, List<Packet> drops) {}

    /**
     * Runs the packets through {@code scheduler} on a link of {@code bytesPerTick}. A packet is tail-dropped on
     * arrival when {@code backlogBytes + size > bufferBytes}.
     */
    public static Result run(List<Packet> packets, Scheduler scheduler, long bytesPerTick, long bufferBytes) {
        if (bytesPerTick < 1) throw new IllegalArgumentException("bytesPerTick must be >= 1");
        Set<Integer> ids = new HashSet<>();
        for (Packet p : packets) {
            if (!ids.add(p.id())) throw new IllegalArgumentException("duplicate packet id " + p.id());
        }
        List<Packet> input = new ArrayList<>(packets);
        input.sort(Comparator.comparingLong(Packet::arrival)); // stable: ties keep input order
        List<Departure> departures = new ArrayList<>();
        List<Packet> drops = new ArrayList<>();
        int next = 0;
        long now = 0;
        while (next < input.size() || !scheduler.isEmpty()) {
            if (scheduler.isEmpty() && input.get(next).arrival() > now) now = input.get(next).arrival();
            while (next < input.size() && input.get(next).arrival() <= now) {
                Packet p = input.get(next++);
                if (scheduler.backlogBytes() + p.size() <= bufferBytes) scheduler.enqueue(p);
                else drops.add(p);
            }
            Packet sent = scheduler.dequeue().orElse(null);
            if (sent == null) continue; // everything that arrived was dropped; jump to the next arrival
            long end = now + (sent.size() + bytesPerTick - 1) / bytesPerTick;
            departures.add(new Departure(sent, now, end));
            now = end;
        }
        return new Result(departures, drops);
    }
}
