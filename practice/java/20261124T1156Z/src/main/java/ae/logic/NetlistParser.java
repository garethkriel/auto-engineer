package ae.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses and validates the netlist text format described in TASK.md. */
public final class NetlistParser {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern DELAY = Pattern.compile("@[0-9]{1,4}");
    private static final int MAX_DELAY = 1000;

    private NetlistParser() {}

    private static boolean badName(String s) {
        if (!NAME.matcher(s).matches() || s.equals("input") || s.equals("output")) return true;
        for (Op op : Op.values()) if (op.name().equals(s)) return true;
        return false;
    }

    public static Netlist parse(String text) {
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        List<Integer> outputLines = new ArrayList<>();
        List<Netlist.Gate> gates = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> outSet = new HashSet<>();

        String[] lines = text.split("\n", -1);
        for (int ln = 1; ln <= lines.length; ln++) {
            String line = lines[ln - 1];
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.strip();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            if (t[0].equals("input") || t[0].equals("output")) {
                boolean isInput = t[0].equals("input");
                if (t.length == 1) throw new NetlistException(ln, t[0] + " needs at least one name");
                for (int i = 1; i < t.length; i++) {
                    String n = t[i];
                    if (isInput) {
                        if (badName(n)) throw new NetlistException(ln, "bad name " + n);
                        if (!names.add(n)) throw new NetlistException(ln, "duplicate name " + n);
                        inputs.add(n);
                    } else {
                        if (!outSet.add(n)) throw new NetlistException(ln, "duplicate output " + n);
                        outputs.add(n);
                        outputLines.add(ln);
                    }
                }
                continue;
            }
            if (badName(t[0])) throw new NetlistException(ln, "bad name " + t[0]);
            if (!names.add(t[0])) throw new NetlistException(ln, "duplicate name " + t[0]);
            if (t.length < 2 || !t[1].equals("=")) throw new NetlistException(ln, "missing equals sign");
            if (t.length < 3) throw new NetlistException(ln, "missing op");
            Op op;
            try {
                op = Op.valueOf(t[2]);
            } catch (IllegalArgumentException e) {
                throw new NetlistException(ln, "unknown op " + t[2]);
            }
            int end = t.length;
            int delay = op == Op.CONST0 || op == Op.CONST1 ? 0 : 1;
            if (t[end - 1].startsWith("@")) {
                String d = t[end - 1];
                if (!DELAY.matcher(d).matches() || Integer.parseInt(d.substring(1)) > MAX_DELAY)
                    throw new NetlistException(ln, "bad delay " + d);
                delay = Integer.parseInt(d.substring(1));
                end--;
            }
            List<String> ins = new ArrayList<>();
            for (int i = 3; i < end; i++) {
                if (t[i].startsWith("@")) throw new NetlistException(ln, "delay must be last");
                ins.add(t[i]);
            }
            String err = op.arityError(ins.size());
            if (err != null) throw new NetlistException(ln, err);
            gates.add(new Netlist.Gate(t[0], op, List.copyOf(ins), delay, ln));
        }

        Map<String, Integer> ids = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) ids.put(inputs.get(i), i);
        for (int j = 0; j < gates.size(); j++) ids.put(gates.get(j).name(), inputs.size() + j);
        for (Netlist.Gate g : gates)
            for (String in : g.ins())
                if (!ids.containsKey(in)) throw new NetlistException(g.line(), "unknown signal " + in);
        for (int i = 0; i < outputs.size(); i++)
            if (!ids.containsKey(outputs.get(i))) throw new NetlistException(outputLines.get(i), "unknown output " + outputs.get(i));

        return new Netlist(inputs, outputs, gates, ids, levelise(inputs.size(), gates, ids));
    }

    /** Kahn's algorithm over non-DFF gates; leftover gates mean a combinational loop. */
    private static int[] levelise(int nIn, List<Netlist.Gate> gates, Map<String, Integer> ids) {
        int n = gates.size();
        int[] pending = new int[n];
        List<List<Integer>> users = new ArrayList<>();
        for (int j = 0; j < n; j++) users.add(new ArrayList<>());
        for (int j = 0; j < n; j++) {
            Netlist.Gate g = gates.get(j);
            if (g.op().isSource()) continue;
            for (String in : g.ins()) {
                int id = ids.get(in);
                if (id >= nIn && !gates.get(id - nIn).op().isSource()) {
                    pending[j]++;
                    users.get(id - nIn).add(j);
                }
            }
        }
        int[] order = new int[n];
        boolean[] done = new boolean[n];
        int count = 0;
        boolean progress = true;
        // sweep in declaration order until stable: deterministic and O(n * depth), fine for netlists
        while (progress) {
            progress = false;
            for (int j = 0; j < n; j++) {
                if (done[j] || gates.get(j).op().isSource() || pending[j] != 0) continue;
                done[j] = true;
                order[count++] = j;
                for (int u : users.get(j)) pending[u]--;
                progress = true;
            }
        }
        int comb = 0;
        for (Netlist.Gate g : gates) if (!g.op().isSource()) comb++;
        if (count == comb) {
            int[] trimmed = new int[count];
            System.arraycopy(order, 0, trimmed, 0, count);
            return trimmed;
        }
        throw loop(nIn, gates, ids, done);
    }

    private static NetlistException loop(int nIn, List<Netlist.Gate> gates, Map<String, Integer> ids, boolean[] done) {
        int start = 0;
        while (done[start] || gates.get(start).op().isSource()) start++;
        List<Integer> path = new ArrayList<>();
        Map<Integer, Integer> at = new HashMap<>();
        int cur = start;
        while (!at.containsKey(cur)) {
            at.put(cur, path.size());
            path.add(cur);
            int next = -1;
            for (String in : gates.get(cur).ins()) {
                int id = ids.get(in);
                int g = id - nIn;
                if (g >= 0 && !done[g] && !gates.get(g).op().isSource()) { next = g; break; }
            }
            cur = next;
        }
        List<Integer> cycle = new ArrayList<>(path.subList(at.get(cur), path.size()));
        int lowest = 0;
        for (int i = 1; i < cycle.size(); i++) if (cycle.get(i) < cycle.get(lowest)) lowest = i;
        StringBuilder sb = new StringBuilder("combinational loop: ");
        for (int i = 0; i <= cycle.size(); i++) {
            if (i > 0) sb.append(" -> ");
            sb.append(gates.get(cycle.get((lowest + i) % cycle.size())).name());
        }
        return new NetlistException(gates.get(cycle.get(lowest)).line(), sb.toString());
    }
}
