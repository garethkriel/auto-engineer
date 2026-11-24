package ae.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Event-driven simulator with transport delays. All inputs and DFF outputs start false and the circuit starts
 * settled. Events at the same instant are applied together, then affected gates are re-evaluated; a gate with
 * delay 0 therefore resolves in further delta cycles at the same time stamp. An evaluation only schedules an event
 * when it differs from the last value scheduled for that signal ("projected"), so glitches show up in the trace.
 */
public final class Simulator {
    /** A value change of one signal at a time. */
    public record Change(long time, String signal, boolean value) {}

    private final Netlist net;
    private final boolean[] values;
    private final boolean[] projected;
    private final int[][] fanout;                       // signal id -> comb gate indices reading it
    private final TreeMap<Long, Map<Integer, Boolean>> queue = new TreeMap<>();
    private final List<Change> trace = new ArrayList<>();
    private long now;

    public Simulator(Netlist net) {
        this.net = net;
        this.values = net.evalValues(Map.of(), Map.of());
        this.projected = values.clone();
        List<TreeSet<Integer>> users = new ArrayList<>();
        for (int i = 0; i < net.signalCount(); i++) users.add(new TreeSet<>());
        for (int j : net.order()) for (int in : net.fanin()[j]) users.get(in).add(j);
        this.fanout = new int[net.signalCount()][];
        for (int i = 0; i < fanout.length; i++) fanout[i] = users.get(i).stream().mapToInt(Integer::intValue).toArray();
    }

    private int idOf(String name) {
        Integer id = net.id(name);
        if (id == null) throw new IllegalArgumentException("unknown signal " + name);
        return id;
    }

    private void schedule(int sig, boolean v, long at) {
        queue.computeIfAbsent(at, k -> new LinkedHashMap<>()).put(sig, v);
        projected[sig] = v;
    }

    /** Requests an input value at the current time; takes effect on the next {@link #run()}. */
    public void setInput(String name, boolean v) {
        int id = idOf(name);
        if (id >= net.inputCount()) throw new IllegalArgumentException("not an input: " + name);
        if (projected[id] != v) schedule(id, v, now);
    }

    public boolean value(String name) { return values[idOf(name)]; }
    public long time() { return now; }
    public List<Change> trace() { return List.copyOf(trace); }

    /** Processes events until none remain; returns the time from the start to the last value change. */
    public long run() {
        long start = now, last = now;
        while (!queue.isEmpty()) {
            Map.Entry<Long, Map<Integer, Boolean>> e = queue.pollFirstEntry();
            now = e.getKey();
            TreeSet<Integer> touched = new TreeSet<>();
            for (Map.Entry<Integer, Boolean> c : e.getValue().entrySet()) {
                int sig = c.getKey();
                if (values[sig] == c.getValue()) continue;
                values[sig] = c.getValue();
                trace.add(new Change(now, net.signalName(sig), c.getValue()));
                last = now;
                for (int g : fanout[sig]) touched.add(g);
            }
            for (int g : touched) {
                Netlist.Gate gate = net.gates().get(g);
                int sig = net.inputCount() + g;
                boolean v = gate.op().apply(values, net.fanin()[g]);
                if (v != projected[sig]) schedule(sig, v, now + gate.delay());
            }
        }
        return last - start;
    }

    /** One clock edge: every DFF samples its D now, Q follows after the DFF's delay. Returns the settle time. */
    public long clock() {
        List<Netlist.Gate> gates = net.gates();
        boolean[] sampled = new boolean[gates.size()];
        for (int j = 0; j < gates.size(); j++)
            if (gates.get(j).op() == Op.DFF) sampled[j] = values[net.fanin()[j][0]];
        for (int j = 0; j < gates.size(); j++) {
            if (gates.get(j).op() != Op.DFF) continue;
            int sig = net.inputCount() + j;
            if (projected[sig] != sampled[j]) schedule(sig, sampled[j], now + gates.get(j).delay());
        }
        return run();
    }
}
