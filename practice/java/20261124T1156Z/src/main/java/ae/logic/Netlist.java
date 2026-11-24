package ae.logic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, validated gate-level netlist. Signals are numbered: inputs first (0..I-1), then gates in
 * declaration order (I + gate index). Combinational gates are kept in a topological order; DFF outputs are
 * sources, so a loop through a DFF is legal. Only {@link NetlistParser} creates instances.
 */
public final class Netlist {
    /** One gate; {@code delay} is the propagation delay (clock-to-q for a DFF). */
    public record Gate(String name, Op op, List<String> ins, int delay, int line) {}

    private final List<String> inputs;
    private final List<String> outputs;
    private final List<Gate> gates;
    private final Map<String, Integer> ids;
    private final int[][] fanin;      // per gate: operand signal ids
    private final int[] order;        // gate indices of non-DFF gates, operands first
    private final int[] outIds;
    private final boolean combinational;

    Netlist(List<String> inputs, List<String> outputs, List<Gate> gates, Map<String, Integer> ids, int[] order) {
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.gates = List.copyOf(gates);
        this.ids = Map.copyOf(ids);
        this.order = order.clone();
        this.fanin = new int[gates.size()][];
        boolean comb = true;
        for (int j = 0; j < gates.size(); j++) {
            Gate g = gates.get(j);
            fanin[j] = new int[g.ins().size()];
            for (int k = 0; k < fanin[j].length; k++) fanin[j][k] = ids.get(g.ins().get(k));
            if (g.op() == Op.DFF) comb = false;
        }
        this.combinational = comb;
        this.outIds = new int[outputs.size()];
        for (int i = 0; i < outIds.length; i++) outIds[i] = ids.get(outputs.get(i));
    }

    public List<String> inputs() { return inputs; }
    public List<String> outputs() { return outputs; }
    public List<Gate> gates() { return gates; }
    public boolean isCombinational() { return combinational; }
    public Optional<Gate> gate(String name) {
        Integer id = ids.get(name);
        return id == null || id < inputs.size() ? Optional.empty() : Optional.of(gates.get(id - inputs.size()));
    }

    // package-private views for Simulator / Timing / Equiv
    int signalCount() { return inputs.size() + gates.size(); }
    int inputCount() { return inputs.size(); }
    int[][] fanin() { return fanin; }
    int[] order() { return order; }
    Integer id(String name) { return ids.get(name); }
    String signalName(int id) { return id < inputs.size() ? inputs.get(id) : gates.get(id - inputs.size()).name(); }

    /** Evaluates the combinational logic; every DFF output is taken from {@code state} (default false). */
    boolean[] evalValues(Map<String, Boolean> in, Map<String, Boolean> state) {
        boolean[] v = new boolean[signalCount()];
        for (var e : in.entrySet()) {
            Integer id = ids.get(e.getKey());
            if (id == null || id >= inputs.size()) throw new IllegalArgumentException("not an input: " + e.getKey());
            v[id] = e.getValue();
        }
        for (var e : state.entrySet()) {
            Integer id = ids.get(e.getKey());
            if (id == null || id < inputs.size() || gates.get(id - inputs.size()).op() != Op.DFF)
                throw new IllegalArgumentException("not a DFF: " + e.getKey());
            v[id] = e.getValue();
        }
        for (int j : order) v[inputs.size() + j] = gates.get(j).op().apply(v, fanin[j]);
        return v;
    }

    /** Output values by name, in output declaration order. */
    public Map<String, Boolean> eval(Map<String, Boolean> in) { return eval(in, Map.of()); }

    public Map<String, Boolean> eval(Map<String, Boolean> in, Map<String, Boolean> state) {
        boolean[] v = evalValues(in, state);
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (int i = 0; i < outIds.length; i++) out.put(outputs.get(i), v[outIds[i]]);
        return out;
    }

    /** Every signal (inputs, then gates) by name. */
    public Map<String, Boolean> evalAll(Map<String, Boolean> in, Map<String, Boolean> state) {
        boolean[] v = evalValues(in, state);
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (int i = 0; i < v.length; i++) out.put(signalName(i), v[i]);
        return out;
    }

    /** 64 vectors at once: bit k of {@code inputWords[i]} is input i in vector k. Returns one word per output. */
    public long[] evalPacked(long[] inputWords) {
        if (!combinational) throw new IllegalStateException("netlist has DFFs");
        if (inputWords.length != inputs.size())
            throw new IllegalArgumentException("expected " + inputs.size() + " words, got " + inputWords.length);
        long[] v = new long[signalCount()];
        System.arraycopy(inputWords, 0, v, 0, inputWords.length);
        for (int j : order) v[inputs.size() + j] = gates.get(j).op().applyPacked(v, fanin[j]);
        long[] out = new long[outIds.length];
        for (int i = 0; i < out.length; i++) out[i] = v[outIds[i]];
        return out;
    }
}
