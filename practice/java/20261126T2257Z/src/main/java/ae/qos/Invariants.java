package ae.qos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks a simulation result against the rules every scheduler must obey. */
public final class Invariants {
    private Invariants() {}

    /** @return violation messages prefixed by rule name; empty when the result is valid */
    public static List<String> check(List<Packet> input, LinkSimulator.Result r, long bytesPerTick) {
        List<String> v = new ArrayList<>();
        Map<Integer, Integer> inputPos = new HashMap<>();
        for (int i = 0; i < input.size(); i++) inputPos.put(input.get(i).id(), i);

        Map<Integer, Integer> seen = new HashMap<>();
        for (LinkSimulator.Departure d : r.departures()) seen.merge(d.packet().id(), 1, Integer::sum);
        for (Packet p : r.drops()) seen.merge(p.id(), 1, Integer::sum);
        for (Packet p : input) {
            int n = seen.getOrDefault(p.id(), 0);
            if (n != 1) v.add("conservation: packet " + p.id() + " accounted " + n + " times");
        }
        for (int id : seen.keySet()) {
            if (!inputPos.containsKey(id)) v.add("conservation: unknown packet " + id);
        }

        List<LinkSimulator.Departure> ds = r.departures();
        for (LinkSimulator.Departure d : ds) {
            if (d.start() < d.packet().arrival()) v.add("early-start: packet " + d.packet().id());
            long expected = (d.packet().size() + bytesPerTick - 1) / bytesPerTick;
            if (d.end() - d.start() != expected) v.add("duration: packet " + d.packet().id());
        }
        long prevEnd = Long.MIN_VALUE;
        for (int k = 0; k < ds.size(); k++) {
            LinkSimulator.Departure d = ds.get(k);
            if (k > 0 && d.start() < prevEnd) v.add("overlap: packet " + d.packet().id());
            if (d.start() > prevEnd) { // idle gap (or start): nobody departing later may have been waiting
                for (int j = k; j < ds.size(); j++) {
                    if (ds.get(j).packet().arrival() < d.start()) {
                        v.add("idle: link idle before " + d.start() + " while packet " + ds.get(j).packet().id() + " waited");
                        break;
                    }
                }
            }
            prevEnd = d.end();
        }
        Map<String, Packet> lastOfFlow = new HashMap<>();
        for (LinkSimulator.Departure d : ds) {
            Packet p = d.packet();
            Packet before = lastOfFlow.put(p.flow(), p);
            if (before != null && inputRank(before, inputPos) > inputRank(p, inputPos)) {
                v.add("flow-order: packet " + p.id() + " overtook packet " + before.id());
            }
        }
        return v;
    }

    private static long inputRank(Packet p, Map<Integer, Integer> inputPos) {
        // stable order: arrival, then input position
        return p.arrival() * 1_000_003L + inputPos.getOrDefault(p.id(), 0);
    }
}
