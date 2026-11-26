package ae.qos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-flow token-bucket shaping in integer ticks and bytes. */
public final class TokenBucketShaper {
    private TokenBucketShaper() {}

    private static final class State {
        long tokens;
        long time;
        long lastRelease;
    }

    /**
     * Delays each packet until its flow's bucket (capacity {@code burst}, refill {@code rate} per tick, initially
     * full) holds {@code size} tokens, keeping per-flow order. Returns packets with {@code arrival} set to the
     * release tick, ordered by (release, position in the arrival-sorted input).
     */
    public static List<Packet> shape(List<Packet> packets, long rate, long burst) {
        if (rate < 1) throw new IllegalArgumentException("rate must be >= 1");
        if (burst < 1) throw new IllegalArgumentException("burst must be >= 1");
        List<Packet> sorted = new ArrayList<>(packets);
        sorted.sort(Comparator.comparingLong(Packet::arrival)); // stable
        for (Packet p : sorted) {
            if (p.size() > burst) throw new IllegalArgumentException("packet " + p.id() + " larger than burst");
        }
        Map<String, State> states = new HashMap<>();
        record Released(Packet packet, int pos) {}
        List<Released> out = new ArrayList<>();
        for (int pos = 0; pos < sorted.size(); pos++) {
            Packet p = sorted.get(pos);
            State s = states.computeIfAbsent(p.flow(), k -> {
                State fresh = new State();
                fresh.tokens = burst;
                return fresh;
            });
            long t = Math.max(p.arrival(), s.lastRelease);
            long tokens = Math.min(burst, s.tokens + Math.multiplyExact(rate, t - s.time));
            if (tokens < p.size()) {
                long wait = (p.size() - tokens + rate - 1) / rate;
                t += wait;
                tokens = Math.min(burst, tokens + rate * wait);
            }
            s.tokens = tokens - p.size();
            s.time = t;
            s.lastRelease = t;
            out.add(new Released(new Packet(p.id(), p.flow(), p.size(), t), pos));
        }
        out.sort(Comparator.<Released>comparingLong(r -> r.packet.arrival()).thenComparingInt(r -> r.pos));
        List<Packet> result = new ArrayList<>(out.size());
        for (Released r : out) result.add(r.packet);
        return result;
    }
}
