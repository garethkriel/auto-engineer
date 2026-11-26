package ae.qos;

import java.util.Map;
import java.util.TreeMap;

/** Per-flow summary of a simulation. */
public final class Stats {
    private Stats() {}

    /** @param maxDelay largest (departure end - arrival) of the flow's packets */
    public record FlowStats(int packets, long bytes, long maxDelay) {}

    public static Map<String, FlowStats> of(LinkSimulator.Result r) {
        Map<String, FlowStats> out = new TreeMap<>();
        for (LinkSimulator.Departure d : r.departures()) {
            Packet p = d.packet();
            FlowStats old = out.getOrDefault(p.flow(), new FlowStats(0, 0, 0));
            out.put(p.flow(), new FlowStats(old.packets() + 1, old.bytes() + p.size(),
                    Math.max(old.maxDelay(), d.end() - p.arrival())));
        }
        return out;
    }
}
