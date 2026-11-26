package ae.cfg;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Dead-code elimination driven by liveness. */
public final class Dce {
    private Dce() { }

    /** Removes unreachable blocks, then dead definitions until a fixed point. */
    public static Function run(Function f) {
        Function cur = dropUnreachable(f);
        while (true) {
            Function next = removeDeadDefs(cur);
            if (next.equals(cur)) {
                return cur;
            }
            cur = next;
        }
    }

    private static Function dropUnreachable(Function f) {
        Cfg cfg = new Cfg(f);
        List<Block> kept = new ArrayList<>();
        for (int b = 0; b < cfg.size(); b++) {
            if (cfg.reachable(b)) {
                kept.add(f.blocks().get(b));
            }
        }
        return new Function(f.name(), f.params(), kept);
    }

    private static Function removeDeadDefs(Function f) {
        Liveness live = new Liveness(new Cfg(f));
        List<Block> blocks = new ArrayList<>();
        for (int b = 0; b < f.blocks().size(); b++) {
            Block blk = f.blocks().get(b);
            List<Set<String>> after = live.liveAfter(b);
            List<Instr> kept = new ArrayList<>();
            for (int i = 0; i < blk.instrs().size(); i++) {
                Instr ins = blk.instrs().get(i);
                boolean dead = ins.def().isPresent() && !after.get(i).contains(ins.def().get());
                if (!dead) {
                    kept.add(ins);
                }
            }
            blocks.add(new Block(blk.label(), kept));
        }
        return new Function(f.name(), f.params(), blocks);
    }
}
