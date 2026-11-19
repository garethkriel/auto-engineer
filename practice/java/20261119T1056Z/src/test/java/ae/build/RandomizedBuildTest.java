package ae.build;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Differential test: a random layered graph of rules with value-dependent (dynamic) deps is built
 * incrementally across many random input edits and compared to a from-scratch build every time.
 */
class RandomizedBuildTest {

    private static final int INPUTS = 6;
    private static final int RULES = 24;

    /** Describes the graph with plain data so two Builders can be wired identically. */
    private static final class Spec {
        final Map<String, String> inputs = new TreeMap<>();
        // rule r<i> may only need inputs or rules with smaller index => acyclic
        final List<List<String>> candidates = new ArrayList<>();
        final List<Integer> mod = new ArrayList<>();

        Spec(Random rnd) {
            for (int i = 0; i < INPUTS; i++) {
                inputs.put("in" + i, String.valueOf(rnd.nextInt(4)));
            }
            for (int r = 0; r < RULES; r++) {
                List<String> c = new ArrayList<>();
                int n = 2 + rnd.nextInt(3);
                for (int k = 0; k < n; k++) {
                    int pick = rnd.nextInt(INPUTS + r);
                    c.add(pick < INPUTS ? "in" + pick : "r" + (pick - INPUTS));
                }
                candidates.add(c);
                mod.add(2 + rnd.nextInt(3));
            }
        }

        /** r: needs candidate[0]; its value mod m picks which further candidate is needed (dynamic). */
        Builder wire(Map<String, String> currentInputs) {
            Builder b = new Builder();
            currentInputs.forEach(b::input);
            for (int r = 0; r < RULES; r++) {
                final List<String> c = candidates.get(r);
                final int m = mod.get(r);
                b.rule("r" + r, ctx -> {
                    String first = ctx.need(c.get(0));
                    int sel = 1 + Math.floorMod(first.hashCode(), c.size() - 1);
                    String second = ctx.need(c.get(sel));
                    // coarse output so early cutoff happens often
                    return String.valueOf(Math.floorMod((first + "/" + second).hashCode(), m));
                });
            }
            return b;
        }
    }

    @Test
    void incrementalEqualsFromScratchAndRunsMinimalWork() {
        for (long seed = 1; seed <= 25; seed++) {
            Random rnd = new Random(seed);
            Spec spec = new Spec(rnd);
            Map<String, String> inputs = new TreeMap<>(spec.inputs);
            Builder inc = spec.wire(inputs);
            List<String> targets = List.of("r" + (RULES - 1), "r" + (RULES - 2), "r" + (RULES / 2));

            // Inputs edited at any point so far. A rule unreached for a while may rerun on a stale
            // record, so the per-step set would be too strict; the cumulative set is sound.
            Set<String> everChanged = new HashSet<>();
            for (int step = 0; step < 30; step++) {
                String ctxMsg = "seed " + seed + " step " + step;
                Set<String> changed = new HashSet<>();
                int edits = rnd.nextInt(3); // 0 edits happens often: the no-op run
                for (int e = 0; e < edits; e++) {
                    String k = "in" + rnd.nextInt(INPUTS);
                    String v = String.valueOf(rnd.nextInt(4));
                    if (!v.equals(inputs.get(k))) {
                        changed.add(k);
                    }
                    inputs.put(k, v);
                    inc.input(k, v);
                }
                Set<String> hadRecord = new HashSet<>(inc.store().keys());
                everChanged.addAll(changed);
                List<String> got = inc.build(targets);

                Builder scratch = spec.wire(inputs);
                assertEquals(scratch.build(targets), got, ctxMsg);

                List<String> executed = inc.lastExecuted();
                assertEquals(new HashSet<>(executed).size(), executed.size(), "a rule ran twice: " + ctxMsg);
                assertTrue(executed.size() <= scratch.lastExecuted().size(), ctxMsg);
                if (changed.isEmpty() && step > 0) {
                    assertEquals(List.of(), executed, "nothing changed, nothing may run: " + ctxMsg);
                }
                // every executed rule has a recorded dependency chain down to a changed input
                // (the post-run records are the ones that ran, so check against the scratch graph)
                for (String rule : executed) {
                    // a rule with no earlier record (newly reached by a flipped dynamic dep) may run
                    // without any changed input below it
                    assertTrue(!hadRecord.contains(rule) || reaches(inc, rule, everChanged, new HashSet<>()),
                            rule + " ran without a changed input below it: " + ctxMsg);
                }
            }
        }
    }

    /** True when {@code key} transitively depends (via current records) on a changed input. */
    private static boolean reaches(Builder b, String key, Set<String> changed, Set<String> seen) {
        if (changed.contains(key)) {
            return true;
        }
        if (!seen.add(key)) {
            return false;
        }
        Store.RecordView rec = b.store().record(key);
        if (rec == null) {
            return false;
        }
        for (String d : rec.depKeys()) {
            if (reaches(b, d, changed, seen)) {
                return true;
            }
        }
        return false;
    }
}
