package ae.deps;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Brute-force differential tests: enumerate every assignment on tiny random registries. */
class ResolverOracleTest {
    private static final String[] POOL = {"^1.0.0", "^2.0.0", "~1.1.0", ">=1.0.0 <3.0.0", "1.x", "*", "2.0.0 || 3.0.0", "=3.0.0", ">=2.0.0"};
    private static final String[] VERSIONS = {"1.0.0", "1.1.0", "1.2.0", "2.0.0", "3.0.0"};
    private static final int N = 5;

    private static String name(int i) {
        return "p" + i;
    }

    /** Independent validity check: every root satisfied, every chosen package's deps chosen and satisfied. */
    static boolean valid(Registry reg, Map<String, Constraint> roots, Map<String, Version> chosen) {
        for (var e : roots.entrySet()) {
            Version v = chosen.get(e.getKey());
            if (v == null || !e.getValue().matches(v)) return false;
        }
        for (var e : chosen.entrySet()) {
            Package p = reg.find(e.getKey(), e.getValue()).orElse(null);
            if (p == null) return false;
            for (var d : p.deps().entrySet()) {
                Version dv = chosen.get(d.getKey());
                if (dv == null || !d.getValue().matches(dv)) return false;
            }
        }
        return true;
    }

    private static boolean oracleSatisfiable(InMemoryRegistry reg, Map<String, Constraint> roots) {
        List<List<Version>> options = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            List<Version> o = new ArrayList<>();
            o.add(null);
            o.addAll(reg.versions(name(i)));
            options.add(o);
        }
        int[] idx = new int[N];
        while (true) {
            Map<String, Version> chosen = new TreeMap<>();
            for (int i = 0; i < N; i++) {
                Version v = options.get(i).get(idx[i]);
                if (v != null) chosen.put(name(i), v);
            }
            if (valid(reg, roots, chosen)) return true;
            int k = 0;
            while (k < N && ++idx[k] == options.get(k).size()) idx[k++] = 0;
            if (k == N) return false;
        }
    }

    @Test
    void agreesWithBruteForceOnRandomRegistries() {
        int sat = 0, unsat = 0;
        for (long seed = 0; seed < 400; seed++) {
            Random rnd = new Random(seed);
            InMemoryRegistry reg = new InMemoryRegistry();
            for (int i = 0; i < N; i++) {
                for (String v : VERSIONS) {
                    if (rnd.nextInt(10) < 4) continue; // ~60% of versions exist
                    List<String> deps = new ArrayList<>();
                    java.util.Set<Integer> used = new TreeSet<>();
                    int nd = rnd.nextInt(3);
                    for (int d = 0; d < nd; d++) {
                        int target = rnd.nextInt(N);
                        if (target == i || !used.add(target)) continue;
                        deps.add(name(target));
                        deps.add(POOL[rnd.nextInt(POOL.length)]);
                    }
                    reg.add(name(i), v, deps.toArray(new String[0]));
                }
            }
            Map<String, Constraint> roots = new LinkedHashMap<>();
            roots.put(name(rnd.nextInt(N)), Constraint.parse(POOL[rnd.nextInt(POOL.length)]));
            if (rnd.nextBoolean()) roots.put(name(rnd.nextInt(N)), Constraint.parse(POOL[rnd.nextInt(POOL.length)]));

            boolean expected = oracleSatisfiable(reg, roots);
            try {
                Resolution r = new Resolver(reg).resolve(roots);
                assertTrue(expected, "seed " + seed + ": resolver found a solution the oracle says cannot exist");
                assertTrue(valid(reg, roots, r.versions()), "seed " + seed + ": invalid resolution " + r.versions());
                assertEquals(closure(reg, roots.keySet(), r.versions()), new TreeSet<>(r.versions().keySet()),
                    "seed " + seed + ": resolution must contain exactly the reachable packages");
                assertEquals(new TreeSet<>(r.versions().keySet()), new TreeSet<>(r.installOrder()), "seed " + seed);
                assertEquals(r.versions().size(), r.installOrder().size(), "seed " + seed + ": order has duplicates");
                // determinism
                assertEquals(r.versions(), new Resolver(reg).resolve(roots).versions(), "seed " + seed);
                sat++;
            } catch (ResolutionException e) {
                assertFalse(expected, "seed " + seed + ": oracle has a solution but resolver said " + e.getMessage());
                unsat++;
            }
        }
        // the generator must exercise both outcomes, otherwise the comparison proves little
        assertTrue(sat >= 60, "satisfiable cases: " + sat);
        assertTrue(unsat >= 60, "unsatisfiable cases: " + unsat);
    }

    private static Set<String> closure(Registry reg, Set<String> rootNames, Map<String, Version> chosen) {
        Set<String> seen = new TreeSet<>();
        List<String> todo = new ArrayList<>(rootNames);
        while (!todo.isEmpty()) {
            String n = todo.remove(todo.size() - 1);
            if (!seen.add(n)) continue;
            todo.addAll(reg.find(n, chosen.get(n)).orElseThrow().deps().keySet());
        }
        return seen;
    }

    @Test
    void installOrderPutsDependenciesFirstWhenAcyclic() throws Exception {
        for (long seed = 0; seed < 100; seed++) {
            Random rnd = new Random(seed);
            InMemoryRegistry reg = new InMemoryRegistry();
            // acyclic by construction: p(i) only depends on p(j) with j > i
            for (int i = 0; i < 8; i++) {
                List<String> deps = new ArrayList<>();
                for (int j = i + 1; j < 8; j++) {
                    if (rnd.nextInt(3) == 0) {
                        deps.add(name(j));
                        deps.add("*");
                    }
                }
                reg.add(name(i), "1.0.0", deps.toArray(new String[0]));
            }
            Map<String, Constraint> roots = new LinkedHashMap<>();
            for (int i = 0; i < 8; i++) roots.put(name(i), Constraint.parse("*"));
            Resolution r = new Resolver(reg).resolve(roots);
            Map<String, Integer> pos = new TreeMap<>();
            for (int i = 0; i < r.installOrder().size(); i++) pos.put(r.installOrder().get(i), i);
            for (String n : r.versions().keySet()) {
                for (String d : reg.find(n, r.versions().get(n)).orElseThrow().deps().keySet()) {
                    assertTrue(pos.get(d) < pos.get(n), "seed " + seed + ": " + d + " must precede " + n + " in " + r.installOrder());
                }
            }
        }
    }

    @Test
    void lockedResolutionIsAlwaysValidAndPrefersLock() throws Exception {
        for (long seed = 0; seed < 200; seed++) {
            Random rnd = new Random(1000 + seed);
            InMemoryRegistry reg = new InMemoryRegistry();
            for (int i = 0; i < 4; i++) {
                for (String v : VERSIONS) {
                    if (rnd.nextInt(10) < 3) continue;
                    List<String> deps = new ArrayList<>();
                    int t = rnd.nextInt(4);
                    if (t != i && rnd.nextBoolean()) {
                        deps.add(name(t));
                        deps.add(POOL[rnd.nextInt(POOL.length)]);
                    }
                    reg.add(name(i), v, deps.toArray(new String[0]));
                }
            }
            Map<String, Constraint> roots = Map.of(name(rnd.nextInt(4)), Constraint.parse("*"));
            Resolution base;
            try {
                base = new Resolver(reg).resolve(roots);
            } catch (ResolutionException e) {
                continue;
            }
            // re-resolving with its own result as the preference must reproduce it exactly
            assertEquals(base.versions(), new Resolver(reg).resolve(roots, base.versions()).versions(), "seed " + seed);
            // and any random subset of the lock still gives a valid solution
            Map<String, Version> partial = new TreeMap<>(base.versions());
            partial.keySet().removeIf(k -> rnd.nextBoolean());
            Resolution again = new Resolver(reg).resolve(roots, partial);
            assertTrue(valid(reg, roots, again.versions()), "seed " + seed);
        }
    }
}
