package ae.cfg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Interference graphs and Briggs-style optimistic graph colouring. */
public final class RegAlloc {
    private RegAlloc() { }

    /** Registers of the coloured variables and the set of variables that found none. */
    public record Allocation(Map<String, Integer> regs, Set<String> spilled) {
        public Allocation {
            regs = Map.copyOf(regs);
            spilled = Set.copyOf(spilled);
        }
    }

    /** Interference graph over every variable of {@code f}, as symmetric sorted adjacency sets. */
    public static Map<String, Set<String>> interference(Function f) {
        Cfg cfg = new Cfg(f);
        Liveness live = new Liveness(cfg);
        Map<String, Set<String>> g = new TreeMap<>();
        for (String p : f.params()) {
            g.computeIfAbsent(p, k -> new TreeSet<>());
        }
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                i.def().ifPresent(d -> g.computeIfAbsent(d, k -> new TreeSet<>()));
                for (String u : i.uses()) {
                    g.computeIfAbsent(u, k -> new TreeSet<>());
                }
            }
        }
        for (int bi = 0; bi < f.blocks().size(); bi++) {
            List<Instr> ins = f.blocks().get(bi).instrs();
            for (int k = 0; k < ins.size(); k++) {
                Instr i = ins.get(k);
                if (i.def().isEmpty()) {
                    continue;
                }
                String d = i.def().get();
                for (String v : live.liveAfter(bi).get(k)) {
                    boolean copySource = i instanceof Instr.Copy c && c.src().equals(v);
                    if (!v.equals(d) && !copySource) {
                        edge(g, d, v);
                    }
                }
            }
        }
        List<String> params = f.params();
        for (int a = 0; a < params.size(); a++) {
            for (int b = a + 1; b < params.size(); b++) {
                edge(g, params.get(a), params.get(b));
            }
            for (String v : live.liveIn(0)) {
                if (!v.equals(params.get(a))) {
                    edge(g, params.get(a), v);
                }
            }
        }
        return g;
    }

    private static void edge(Map<String, Set<String>> g, String a, String b) {
        g.get(a).add(b);
        g.get(b).add(a);
    }

    public static Allocation allocate(Function f, int k) {
        return color(interference(f), k);
    }

    /** Optimistic colouring with {@code k} registers; see TASK.md for the exact selection rules. */
    public static Allocation color(Map<String, Set<String>> graph, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1");
        }
        Map<String, Integer> degree = new TreeMap<>();
        graph.forEach((v, ns) -> degree.put(v, ns.size()));
        List<String> stack = new ArrayList<>();
        Set<String> remaining = new TreeSet<>(graph.keySet());
        while (!remaining.isEmpty()) {
            String pick = remaining.stream().filter(v -> degree.get(v) < k)
                    .min(Comparator.<String>comparingInt(degree::get).thenComparing(Comparator.naturalOrder()))
                    .orElseGet(() -> remaining.stream()
                            .max(Comparator.<String>comparingInt(degree::get).thenComparing(Comparator.reverseOrder()))
                            .orElseThrow());
            remaining.remove(pick);
            stack.add(pick);
            for (String n : graph.get(pick)) {
                if (remaining.contains(n)) {
                    degree.merge(n, -1, Integer::sum);
                }
            }
        }
        Map<String, Integer> regs = new TreeMap<>();
        Set<String> spilled = new TreeSet<>();
        for (int s = stack.size() - 1; s >= 0; s--) {
            String v = stack.get(s);
            boolean[] used = new boolean[k];
            for (String n : graph.get(v)) {
                Integer r = regs.get(n);
                if (r != null) {
                    used[r] = true;
                }
            }
            int reg = 0;
            while (reg < k && used[reg]) {
                reg++;
            }
            if (reg == k) {
                spilled.add(v);
            } else {
                regs.put(v, reg);
            }
        }
        return new Allocation(regs, spilled);
    }

    /** Rewrites every variable to {@code r<reg>}; the allocation must have no spills. */
    public static Function rename(Function f, Allocation a) {
        if (!a.spilled().isEmpty()) {
            throw new IllegalArgumentException("cannot rename with spilled variables " + a.spilled());
        }
        java.util.function.UnaryOperator<String> r = v -> {
            Integer reg = a.regs().get(v);
            if (reg == null) {
                throw new IllegalArgumentException("no register for " + v);
            }
            return "r" + reg;
        };
        List<String> params = f.params().stream().map(r).toList();
        List<Block> blocks = new ArrayList<>();
        for (Block b : f.blocks()) {
            List<Instr> ins = new ArrayList<>();
            for (Instr i : b.instrs()) {
                ins.add(switch (i) {
                    case Instr.Const c -> new Instr.Const(r.apply(c.dst()), c.value());
                    case Instr.Bin x -> new Instr.Bin(r.apply(x.dst()), x.op(), r.apply(x.a()), r.apply(x.b()));
                    case Instr.Copy c -> new Instr.Copy(r.apply(c.dst()), r.apply(c.src()));
                    case Instr.Br x -> new Instr.Br(r.apply(x.cond()), x.thenLabel(), x.elseLabel());
                    case Instr.Jmp j -> j;
                    case Instr.Ret x -> new Instr.Ret(r.apply(x.value()));
                });
            }
            blocks.add(new Block(b.label(), ins));
        }
        return new Function(f.name(), params, blocks);
    }
}
