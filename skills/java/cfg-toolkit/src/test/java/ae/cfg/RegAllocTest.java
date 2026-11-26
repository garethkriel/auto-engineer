package ae.cfg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class RegAllocTest {
    private static final String STRAIGHT = """
            func r(a, b)
            entry:
              x = add a, b
              y = mul x, a
              z = add y, b
              ret z
            """;

    private static Map<String, Set<String>> graph(String... edges) {
        Map<String, Set<String>> g = new TreeMap<>();
        for (String e : edges) {
            String[] p = e.split("-");
            g.computeIfAbsent(p[0], k -> new TreeSet<>()).add(p[1]);
            g.computeIfAbsent(p[1], k -> new TreeSet<>()).add(p[0]);
        }
        return g;
    }

    @Test
    void interferenceOfStraightLineCode() {
        // after x=add: {a,b,x} -> x-a, x-b; after y=mul: {b,y} -> y-b; after z: {z}. Params a-b.
        Map<String, Set<String>> g = RegAlloc.interference(Function.parse(STRAIGHT));
        assertEquals(Map.of(
                "a", Set.of("b", "x"),
                "b", Set.of("a", "x", "y"),
                "x", Set.of("a", "b"),
                "y", Set.of("b"),
                "z", Set.of()), g);
    }

    @Test
    void copyDoesNotInterfereWithItsSource() {
        Function f = Function.parse("func c(a)\nb:\n  x = copy a\n  y = add x, a\n  ret y\n");
        // After the copy both a and x are live (a is read by y) but they hold the same value.
        assertEquals(Map.of("a", Set.of(), "x", Set.of(), "y", Set.of()), RegAlloc.interference(f));
        // Control: an add instead of a copy makes them interfere.
        Function g = Function.parse("func c(a)\nb:\n  x = add a, a\n  y = add x, a\n  ret y\n");
        assertEquals(Set.of("x"), RegAlloc.interference(g).get("a"));
    }

    @Test
    void deadDefinitionStillInterferesWithLiveVariables() {
        Function f = Function.parse("func c(a)\nb:\n  dead = const 9\n  ret a\n");
        assertEquals(Set.of("a"), RegAlloc.interference(f).get("dead"));
    }

    @Test
    void parametersInterfereEvenWhenOneIsDead() {
        Function f = Function.parse("func p(a, b, c)\nb0:\n  ret c\n");
        Map<String, Set<String>> g = RegAlloc.interference(f);
        assertEquals(Set.of("b", "c"), g.get("a"));
        assertEquals(Set.of("a", "c"), g.get("b"));
        assertEquals(Set.of("a", "b"), g.get("c"));
    }

    @Test
    void liveThroughLoopVariablesInterfere() {
        Map<String, Set<String>> g = RegAlloc.interference(Function.parse(Fixtures.LOOP));
        assertEquals(Set.of("c", "i", "n"), g.get("one"));   // c = lt i, n is defined while `one` is live around the loop
        assertTrue(g.get("c").containsAll(Set.of("i", "n", "one")));
        assertEquals(Set.of("c", "n", "one"), g.get("i"));   // i vs c: c = lt i, n defines c while i is live
    }

    @Test
    void colourWithThreeAndTwoRegisters() {
        Function f = Function.parse(STRAIGHT);
        RegAlloc.Allocation three = RegAlloc.allocate(f, 3);
        assertEquals(Set.of(), three.spilled());
        assertEquals(Map.of("a", 2, "b", 1, "x", 0, "y", 0, "z", 0), three.regs());
        // k = 2: simplify z(0), y(1); then a,b,x all have degree 2 -> potential spill a (ties: smaller name);
        // pop x->0, b->1, a: neighbours b(1), x(0) -> spilled; y->0 (b=1); z->0.
        RegAlloc.Allocation two = RegAlloc.allocate(f, 2);
        assertEquals(Set.of("a"), two.spilled());
        assertEquals(Map.of("x", 0, "b", 1, "y", 0, "z", 0), two.regs());
    }

    @Test
    void optimisticColouringColoursAFourCycleWithTwoRegisters() {
        // Every node has degree 2 = k, so nothing simplifies: a potential spill is pushed, yet the cycle is 2-colourable.
        RegAlloc.Allocation a = RegAlloc.color(graph("a-b", "b-c", "c-d", "d-a"), 2);
        assertEquals(Set.of(), a.spilled());
        assertValid(graph("a-b", "b-c", "c-d", "d-a"), a);
    }

    @Test
    void oddCycleAndCliqueMustSpill() {
        Map<String, Set<String>> c5 = graph("a-b", "b-c", "c-d", "d-e", "e-a");
        RegAlloc.Allocation a = RegAlloc.color(c5, 2);
        assertEquals(1, a.spilled().size());
        assertValid(c5, a);
        Map<String, Set<String>> k4 = graph("a-b", "a-c", "a-d", "b-c", "b-d", "c-d");
        RegAlloc.Allocation b = RegAlloc.color(k4, 3);
        assertEquals(Set.of("a"), b.spilled());           // potential spill is the smallest name among equal degrees
        assertEquals(Map.of("b", 2, "c", 1, "d", 0), b.regs());
        assertEquals(Set.of(), RegAlloc.color(k4, 4).spilled());
    }

    /**
     * Degrees a2 b2 c3 d3 with k = 2: nothing has degree below k, so the largest degree (tie: smaller name) c is pushed as
     * a potential spill; then a(1), b(1), d(0) simplify in that order. Pops: d->0, b->1, a->1, c has neighbours using 0 and 1.
     * A "degree <= k simplifies" variant would push a first and spill a and b instead.
     */
    @Test
    void potentialSpillIsTheHighestDegreeNode() {
        Map<String, Set<String>> g = graph("a-c", "a-d", "b-c", "b-d", "c-d");
        RegAlloc.Allocation a = RegAlloc.color(g, 2);
        assertEquals(Set.of("c"), a.spilled());
        assertEquals(Map.of("d", 0, "b", 1, "a", 1), a.regs());
    }

    /** A second, list-based implementation of the selection rules in TASK.md, compared exactly on random graphs. */
    private static RegAlloc.Allocation oracleColour(Map<String, Set<String>> g, int k) {
        java.util.List<String> names = new java.util.ArrayList<>(g.keySet());
        Map<String, Integer> deg = new TreeMap<>();
        for (String v : names) {
            deg.put(v, g.get(v).size());
        }
        java.util.List<String> stack = new java.util.ArrayList<>();
        while (!names.isEmpty()) {
            String best = null;
            for (String v : names) {                       // smallest degree below k, ties by smaller name
                if (deg.get(v) < k && (best == null || deg.get(v) < deg.get(best)
                        || deg.get(v).equals(deg.get(best)) && v.compareTo(best) < 0)) {
                    best = v;
                }
            }
            if (best == null) {
                for (String v : names) {                   // largest degree, ties by smaller name
                    if (best == null || deg.get(v) > deg.get(best)
                            || deg.get(v).equals(deg.get(best)) && v.compareTo(best) < 0) {
                        best = v;
                    }
                }
            }
            names.remove(best);
            stack.add(best);
            for (String n : g.get(best)) {
                if (names.contains(n)) {
                    deg.put(n, deg.get(n) - 1);
                }
            }
        }
        Map<String, Integer> regs = new TreeMap<>();
        Set<String> spilled = new TreeSet<>();
        java.util.Collections.reverse(stack);
        for (String v : stack) {
            boolean[] taken = new boolean[k];
            for (String n : g.get(v)) {
                if (regs.containsKey(n)) {
                    taken[regs.get(n)] = true;
                }
            }
            int r = 0;
            while (r < k && taken[r]) {
                r++;
            }
            if (r == k) {
                spilled.add(v);
            } else {
                regs.put(v, r);
            }
        }
        return new RegAlloc.Allocation(regs, spilled);
    }

    @Test
    void colouringMatchesTheSecondImplementationExactly() {
        Random rnd = new Random(909L);
        int differentFromPlainGreedyOrder = 0;
        for (int round = 0; round < 400; round++) {
            int n = 3 + rnd.nextInt(8);
            Map<String, Set<String>> g = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                g.put("n" + i, new TreeSet<>());
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (rnd.nextInt(2) == 0) {
                        g.get("n" + i).add("n" + j);
                        g.get("n" + j).add("n" + i);
                    }
                }
            }
            int k = 2 + rnd.nextInt(3);
            RegAlloc.Allocation got = RegAlloc.color(g, k);
            RegAlloc.Allocation want = oracleColour(g, k);
            assertEquals(want, got, "round " + round + " k=" + k + " graph " + g);
            differentFromPlainGreedyOrder += got.spilled().isEmpty() ? 0 : 1;
        }
        assertTrue(differentFromPlainGreedyOrder > 100, "runs with spills: " + differentFromPlainGreedyOrder);
    }

    @Test
    void parametersInterfereWithVariablesReadBeforeAnyDefinition() {
        // x is defined on one path only, so it is live into the entry; parameter a must not share its register.
        Function f = Function.parse("func f(a)\nb:\n  br a, c, d\nc:\n  x = const 1\n  jmp d\nd:\n  ret x\n");
        assertEquals(Set.of("x"), RegAlloc.interference(f).get("a"));
        assertEquals(Set.of("a"), RegAlloc.interference(f).get("x"));
    }

    @Test
    void isolatedNodesAndBadArguments() {
        Map<String, Set<String>> g = new TreeMap<>();
        g.put("lonely", new TreeSet<>());
        assertEquals(Map.of("lonely", 0), RegAlloc.color(g, 1).regs());
        assertThrows(IllegalArgumentException.class, () -> RegAlloc.color(g, 0));
        assertThrows(IllegalArgumentException.class, () -> RegAlloc.allocate(Function.parse(STRAIGHT), -1));
    }

    @Test
    void renameRequiresACompleteAllocation() {
        Function f = Function.parse(STRAIGHT);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RegAlloc.rename(f, RegAlloc.allocate(f, 2)));
        assertTrue(e.getMessage().contains("spilled") && e.getMessage().contains("[a]"), e.getMessage());
        Function r = RegAlloc.rename(f, RegAlloc.allocate(f, 3));
        assertEquals("""
                func r(r2, r1)
                entry:
                  r0 = add r2, r1
                  r0 = mul r0, r2
                  r0 = add r0, r1
                  ret r0
                """, r.toText());
        assertEquals(Interp.run(f, new long[] {3, 4}, 100), Interp.run(r, new long[] {3, 4}, 100));
    }

    private static void assertValid(Map<String, Set<String>> g, RegAlloc.Allocation a) {
        for (var e : g.entrySet()) {
            assertTrue(a.regs().containsKey(e.getKey()) ^ a.spilled().contains(e.getKey()), "exactly one of coloured/spilled: " + e.getKey());
            for (String n : e.getValue()) {
                if (a.regs().containsKey(e.getKey()) && a.regs().containsKey(n)) {
                    assertNotEquals(a.regs().get(e.getKey()), a.regs().get(n), e.getKey() + "~" + n);
                }
            }
        }
    }

    @Test
    void randomGraphsColourValidly() {
        Random rnd = new Random(5150L);
        int spilledRuns = 0;
        int cleanRuns = 0;
        for (int round = 0; round < 300; round++) {
            int n = 2 + rnd.nextInt(12);
            Map<String, Set<String>> g = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                g.put("n" + i, new TreeSet<>());
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (rnd.nextInt(3) == 0) {
                        g.get("n" + i).add("n" + j);
                        g.get("n" + j).add("n" + i);
                    }
                }
            }
            int k = 1 + rnd.nextInt(5);
            RegAlloc.Allocation a = RegAlloc.color(g, k);
            assertValid(g, a);
            for (int reg : a.regs().values()) {
                assertTrue(reg >= 0 && reg < k);
            }
            if (k >= n) {
                assertEquals(Set.of(), a.spilled(), "k >= n never spills");
            }
            spilledRuns += a.spilled().isEmpty() ? 0 : 1;
            cleanRuns += a.spilled().isEmpty() ? 1 : 0;
        }
        assertTrue(spilledRuns > 50 && cleanRuns > 50, "spilled " + spilledRuns + " clean " + cleanRuns);
    }

    @Test
    void renamedProgramsComputeTheSameResults() {
        Random rnd = new Random(8675309L);
        int renamed = 0;
        int tight = 0;
        for (int round = 0; round < 150; round++) {
            Function f = Function.parse(Gen.structured(rnd, 2, 6));
            int vars = Oracles.allVars(f).size();
            for (int k : new int[] {vars, 6, 4}) {
                RegAlloc.Allocation a = RegAlloc.allocate(f, k);
                if (!a.spilled().isEmpty()) {
                    assertTrue(k < vars);
                    continue;
                }
                Function g = RegAlloc.rename(f, a);
                renamed++;
                tight += k < vars ? 1 : 0;
                for (long[] args : Gen.argSets(rnd, 2, 5)) {
                    Optional<Long> want = Interp.run(f, args, 20_000);
                    assertEquals(want, Interp.run(g, args, 20_000), "round " + round + " k=" + k + "\n" + f.toText() + "\n--\n" + g.toText());
                }
            }
        }
        assertTrue(renamed > 150, "renamed programs: " + renamed);
        assertTrue(tight > 15, "renamed with fewer registers than variables: " + tight);
    }

    /** Negative control: collapsing every non-parameter variable into one register must be caught by the same equivalence check. */
    @Test
    void aWrongAllocationIsDetectedByTheInterpreter() {
        Random rnd = new Random(2024L);
        int caught = 0;
        for (int round = 0; round < 60; round++) {
            Function f = Function.parse(Gen.structured(rnd, 2, 5));
            Map<String, Integer> all = new TreeMap<>();
            for (String v : Oracles.allVars(f)) {
                int pi = f.params().indexOf(v);
                all.put(v, pi >= 0 ? pi : 5);   // distinct parameter registers (a Function rejects duplicates), all else shared
            }
            Function bad = RegAlloc.rename(f, new RegAlloc.Allocation(all, Set.of()));
            for (long[] args : Gen.argSets(rnd, 2, 5)) {
                Optional<Long> good = Interp.run(f, args, 20_000);
                Optional<Long> got;
                try {
                    got = Interp.run(bad, args, 20_000);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    got = Optional.empty();
                }
                if (!good.equals(got)) {
                    caught++;
                    break;
                }
            }
        }
        assertTrue(caught > 50, "wrong allocations detected: " + caught + " of 60");
    }
}
