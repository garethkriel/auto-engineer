package ae.cfg;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Backward live-variable analysis, block level and instruction level. */
public final class Liveness {
    private final List<Set<String>> in = new ArrayList<>();
    private final List<Set<String>> out = new ArrayList<>();
    private final List<List<Set<String>>> after = new ArrayList<>();

    public Liveness(Cfg cfg) {
        Function f = cfg.function();
        int n = cfg.size();
        List<Set<String>> use = new ArrayList<>();   // upward-exposed uses
        List<Set<String>> def = new ArrayList<>();
        for (int b = 0; b < n; b++) {
            Set<String> u = new TreeSet<>();
            Set<String> d = new TreeSet<>();
            for (Instr i : f.blocks().get(b).instrs()) {
                for (String v : i.uses()) {
                    if (!d.contains(v)) {
                        u.add(v);
                    }
                }
                i.def().ifPresent(d::add);
            }
            use.add(u);
            def.add(d);
            in.add(new TreeSet<>());
            out.add(new TreeSet<>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int b = n - 1; b >= 0; b--) {
                Set<String> o = new TreeSet<>();
                for (int s : cfg.succ(b)) {
                    o.addAll(in.get(s));
                }
                Set<String> i = new TreeSet<>(o);
                i.removeAll(def.get(b));
                i.addAll(use.get(b));
                if (!o.equals(out.get(b)) || !i.equals(in.get(b))) {
                    out.set(b, o);
                    in.set(b, i);
                    changed = true;
                }
            }
        }
        for (int b = 0; b < n; b++) {
            List<Instr> ins = f.blocks().get(b).instrs();
            Set<String>[] sets = newArray(ins.size());
            Set<String> live = new TreeSet<>(out.get(b));
            for (int k = ins.size() - 1; k >= 0; k--) {
                sets[k] = new TreeSet<>(live);
                ins.get(k).def().ifPresent(live::remove);
                live.addAll(ins.get(k).uses());
            }
            after.add(List.of(sets));
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String>[] newArray(int n) {
        return (Set<String>[]) new Set<?>[n];
    }

    public Set<String> liveIn(int b) {
        return Set.copyOf(in.get(b)).isEmpty() ? Set.of() : new TreeSet<>(in.get(b));
    }

    public Set<String> liveOut(int b) {
        return new TreeSet<>(out.get(b));
    }

    /** For each instruction of block b, the variables live immediately after it. */
    public List<Set<String>> liveAfter(int b) {
        return after.get(b);
    }
}
