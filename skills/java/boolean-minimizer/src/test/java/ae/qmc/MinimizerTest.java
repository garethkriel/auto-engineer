package ae.qmc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MinimizerTest {
    static List<Integer> l(Integer... xs) { return Arrays.asList(xs); }

    static Set<String> primeStrings(int n, List<Integer> m, List<Integer> d) {
        return Minimizer.primeImplicants(n, m, d).stream().map(i -> i.toString(n)).collect(Collectors.toSet());
    }

    // Textbook: f = sum m(4,8,10,11,12,15) + d(9,14), 4 variables A..D (A = MSB).
    // 4=0100 8=1000 9=1001 10=1010 11=1011 12=1100 14=1110 15=1111
    // primes: AB' {8,9,10,11}, AC {10,11,14,15}, AD' {8,10,12,14}, BC'D' {4,12}.
    @Test
    void textbookPrimes() {
        assertEquals(Set.of("AB'", "AC", "AD'", "BC'D'"),
                primeStrings(4, l(4, 8, 10, 11, 12, 15), l(9, 14)));
    }

    // 4 is covered only by BC'D' (essential). Then 8,10,11,15 need two more terms: AB'+AC or AD'+AC.
    // Cost is (3 terms, 2+2+3 = 7 literals) either way.
    @Test
    void textbookMinimum() {
        Sop s = Minimizer.minimize(4, l(4, 8, 10, 11, 12, 15), l(9, 14));
        assertEquals(3, s.termCount());
        assertEquals(7, s.literalCount(4));
        assertTrue(s.terms().contains(new Implicant(0b0100, 0b1000)), "essential BC'D'");
        assertTrue(s.terms().contains(new Implicant(0b1010, 0b0101)), "AC");
        for (int m : l(4, 8, 10, 11, 12, 15)) assertTrue(s.eval(m), "minterm " + m);
        for (int m : l(0, 1, 2, 3, 5, 6, 7, 13)) assertFalse(s.eval(m), "off " + m);
    }

    // Cyclic core, n=3, sum m(0,1,2,5,6,7): six primes, no essential. Min = 3 terms, 6 literals.
    // 000 A'B'{0,1}  A'C'{0,2}  B'C{1,5}  BC'{2,6}  AC{5,7}  AB{6,7}
    @Test
    void cyclicCore() {
        assertEquals(Set.of("A'B'", "A'C'", "B'C", "BC'", "AC", "AB"),
                primeStrings(3, l(0, 1, 2, 5, 6, 7), l()));
        Sop s = Minimizer.minimize(3, l(0, 1, 2, 5, 6, 7), l());
        assertEquals(3, s.termCount());
        assertEquals(6, s.literalCount(3));
        for (int m = 0; m < 8; m++) assertEquals(m != 3 && m != 4, s.eval(m), "m=" + m);
    }

    @Test
    void parityNeedsEveryMinterm() {
        List<Integer> odd = new ArrayList<>();
        for (int m = 0; m < 16; m++) if (Integer.bitCount(m) % 2 == 1) odd.add(m);
        Sop s = Minimizer.minimize(4, odd, l());
        assertEquals(8, s.termCount());
        assertEquals(32, s.literalCount(4));
        assertEquals(8, Minimizer.primeImplicants(4, odd, l()).size());
    }

    @Test
    void constants() {
        assertEquals("0", Minimizer.minimize(3, l(), l()).toString());
        assertEquals("0", Minimizer.minimize(3, l(), l(1, 2)).toString()); // only don't-cares
        List<Integer> all = new ArrayList<>();
        for (int m = 0; m < 8; m++) all.add(m);
        assertEquals("1", Minimizer.minimize(3, all, l()).toString());
        assertEquals("1", Minimizer.minimize(3, l(0, 3, 5), l(1, 2, 4, 6, 7)).toString());
    }

    @Test
    void singleMinterm() {
        assertEquals("AB'C", Minimizer.minimize(3, l(5), l()).toString());
        // canonical term order is by value: C=001, B=010, A=100
        assertEquals("C + B + A", Minimizer.minimize(3, l(1, 2, 3, 4, 5, 6, 7), l()).toString());
    }

    @Test
    void dontCaresCanShrinkTheAnswer() {
        assertEquals(2, Minimizer.minimize(2, l(0, 3), l()).termCount());
        Sop s = Minimizer.minimize(2, l(1), l(3)); // B (m=01 and d=11)
        assertEquals("B", s.toString());
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> Minimizer.minimize(0, l(), l()));
        assertThrows(IllegalArgumentException.class, () -> Minimizer.minimize(17, l(), l()));
        assertThrows(IllegalArgumentException.class, () -> Minimizer.minimize(3, l(8), l()));
        assertThrows(IllegalArgumentException.class, () -> Minimizer.minimize(3, l(-1), l()));
        assertThrows(IllegalArgumentException.class, () -> Minimizer.minimize(3, l(), l(8)));
        assertThrows(IllegalArgumentException.class, () -> Minimizer.minimize(3, l(1, 1), l()));
        assertThrows(IllegalArgumentException.class, () -> Minimizer.minimize(3, l(), l(2, 2)));
        assertThrows(IllegalArgumentException.class, () -> Minimizer.minimize(3, l(1, 2), l(2)));
        assertThrows(NullPointerException.class, () -> Minimizer.minimize(3, null, l()));
        assertThrows(IllegalArgumentException.class, () -> Minimizer.primeImplicants(3, l(9), l()));
        assertThrows(IllegalArgumentException.class, () -> Minimizer.minimize(3, l(1), l(), -1));
        // controls: legal inputs at the limits
        assertDoesNotThrow(() -> Minimizer.minimize(3, l(7), l(0)));
        assertDoesNotThrow(() -> Minimizer.minimize(16, l(65535), l(0)));
    }

    @Test
    void budgetCountsOnlyBranching() {
        List<Integer> odd = new ArrayList<>();
        for (int m = 0; m < 16; m++) if (Integer.bitCount(m) % 2 == 1) odd.add(m);
        // essentials alone solve parity: zero nodes
        assertEquals(8, Minimizer.minimize(4, odd, l(), 0).termCount());
        // cyclic core needs branching
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Minimizer.minimize(3, l(0, 1, 2, 5, 6, 7), l(), 0));
        assertTrue(e.getMessage().contains("budget"), e.getMessage());
        // Exactly one branching node: after the root branch both sub-problems reduce to essentials (A'B' then
        // BC' and AC, or the mirror image), so a budget of 1 suffices and a budget of 0 does not.
        assertEquals(3, Minimizer.minimize(3, l(0, 1, 2, 5, 6, 7), l(), 1).termCount());
        assertEquals(3, Minimizer.minimize(3, l(0, 1, 2, 5, 6, 7), l(), 1_000).termCount());
    }

    // Regression cases found by running a mutant (prune on equal term count) against the oracle on 20000 random
    // functions: the first solution found had the right term count but too many literals. Expected costs are
    // the oracle's (independent DFS), not the minimiser's.
    @Test
    void equalTermCountStillImprovesLiterals() {
        Fn a = new Fn(5, l(0, 1, 2, 4, 6, 8, 9, 10, 11, 12, 13, 15, 16, 18, 19, 20, 21, 23, 25, 26, 27, 28, 30, 31), l(17));
        Fn b = new Fn(5, l(0, 1, 2, 3, 4, 5, 6, 9, 11, 13, 14, 15, 16, 17, 18, 19, 21, 23, 24, 26, 27, 28, 29, 30, 31), l());
        int[][] expected = {{8, 24}, {7, 20}};
        Fn[] fns = {a, b};
        for (int i = 0; i < 2; i++) {
            Sop s = Minimizer.minimize(5, fns[i].on, fns[i].dc);
            assertValid(fns[i], s);
            assertEquals(expected[i][0], s.termCount(), "terms " + s);
            assertEquals(expected[i][1], s.literalCount(5), "literals " + s);
            assertArrayEquals(expected[i], oracle(fns[i]), "oracle agrees with literal expectation");
        }
    }

    @Test
    void inputOrderDoesNotMatter() {
        Random r = new Random(5);
        for (int t = 0; t < 100; t++) {
            int n = 3 + r.nextInt(3);
            Fn f = Fn.random(r, n, true);
            String base = Minimizer.minimize(n, f.on, f.dc).toString();
            List<Integer> on = new ArrayList<>(f.on), dc = new ArrayList<>(f.dc);
            Collections.shuffle(on, r);
            Collections.shuffle(dc, r);
            assertEquals(base, Minimizer.minimize(n, on, dc).toString(), f.toString());
        }
    }

    // ---- differential against an independent exact oracle ----

    record Fn(int n, List<Integer> on, List<Integer> dc) {
        static Fn random(Random r, int n, boolean withDc) {
            int density = 1 + r.nextInt(7); // out of 8: P(on)
            List<Integer> on = new ArrayList<>(), dc = new ArrayList<>();
            for (int m = 0; m < (1 << n); m++) {
                int x = r.nextInt(8);
                if (x < density) on.add(m);
                else if (withDc && r.nextInt(4) == 0) dc.add(m);
            }
            return new Fn(n, on, dc);
        }
    }

    /**
     * Exact min (terms, literals) by DFS over every implicant that avoids the off-set. No primes, no essentials, no
     * dominance. Covered minterms are tracked as a long bitmask over the (at most 32) on-points.
     */
    static int[] oracle(Fn f) {
        int n = f.n;
        int full = (1 << n) - 1;
        List<Integer> on = f.on;
        List<long[]> valid = new ArrayList<>(); // {coverMask, literals}
        for (int mask = 0; mask <= full; mask++) {
            for (int v = 0; v <= full; v++) {
                if ((v & mask) != 0) continue;
                Implicant i = new Implicant(v, mask);
                boolean ok = true;
                for (int m = 0; m <= full && ok; m++)
                    if (i.covers(m) && !f.on.contains(m) && !f.dc.contains(m)) ok = false;
                if (!ok) continue;
                long bits = 0;
                for (int k = 0; k < on.size(); k++) if (i.covers(on.get(k))) bits |= 1L << k;
                valid.add(new long[] {bits, i.literals(n)});
            }
        }
        long goal = on.size() == 64 ? -1L : (1L << on.size()) - 1;
        int[] best = {Integer.MAX_VALUE, Integer.MAX_VALUE};
        dfs(goal, valid, 0L, 0, 0, best);
        return best;
    }

    static void dfs(long goal, List<long[]> valid, long covered, int terms, int lits, int[] best) {
        if (covered == goal) {
            if (terms < best[0] || (terms == best[0] && lits < best[1])) { best[0] = terms; best[1] = lits; }
            return;
        }
        if (terms + 1 > best[0] || (terms + 1 == best[0] && lits >= best[1])) return;
        long target = Long.lowestOneBit(goal & ~covered);
        for (long[] i : valid) {
            if ((i[0] & target) == 0) continue;
            dfs(goal, valid, covered | i[0], terms + 1, lits + (int) i[1], best);
        }
    }

    static void assertValid(Fn f, Sop s) {
        for (int m = 0; m < (1 << f.n); m++) {
            if (f.on.contains(m)) assertTrue(s.eval(m), "minterm " + m + " uncovered in " + s + " for " + f);
            else if (!f.dc.contains(m)) assertFalse(s.eval(m), "off point " + m + " covered in " + s + " for " + f);
        }
    }

    @Test
    void matchesBruteForceOracle() {
        Random r = new Random(20261125);
        int withDc = 0, multi = 0, nonTrivial = 0;
        for (int t = 0; t < 600; t++) {
            int n = 2 + r.nextInt(3) + (t % 12 == 0 ? 1 : 0); // 2..5 variables, sometimes 5
            Fn f = Fn.random(r, n, t % 2 == 0);
            Sop s = Minimizer.minimize(n, f.on, f.dc);
            assertValid(f, s);
            int[] best = oracle(f);
            assertEquals(best[0], s.termCount(), "terms for " + f + " got " + s);
            assertEquals(best[1], s.literalCount(n), "literals for " + f + " got " + s);
            if (!f.dc.isEmpty()) withDc++;
            if (s.termCount() > 1) multi++;
            if (s.termCount() > 0 && s.literalCount(n) > 0) nonTrivial++;
        }
        // outcome mix of the generator (rule: measure before trusting)
        assertTrue(withDc > 150, "cases with don't-cares: " + withDc);
        assertTrue(multi > 300, "multi-term answers: " + multi);
        assertTrue(nonTrivial > 400, "non-constant answers: " + nonTrivial);
    }

    @Test
    void primesAreExactlyTheMaximalValidImplicants() {
        Random r = new Random(77);
        for (int t = 0; t < 150; t++) {
            int n = 2 + r.nextInt(4); // 2..5
            Fn f = Fn.random(r, n, true);
            Set<Integer> care = new HashSet<>(f.on);
            care.addAll(f.dc);
            List<Implicant> primes = Minimizer.primeImplicants(n, f.on, f.dc);
            // reference: all implicants with every covered point in care, maximal under containment
            Set<Implicant> expected = new HashSet<>();
            int full = (1 << n) - 1;
            List<Implicant> ok = new ArrayList<>();
            for (int mask = 0; mask <= full; mask++)
                for (int v = 0; v <= full; v++) {
                    if ((v & mask) != 0) continue;
                    Implicant i = new Implicant(v, mask);
                    boolean inside = true, any = false;
                    for (int m = 0; m <= full; m++) if (i.covers(m)) { any = true; if (!care.contains(m)) inside = false; }
                    if (inside && any) ok.add(i);
                }
            for (Implicant i : ok) {
                boolean maximal = true;
                for (Implicant j : ok)
                    if (!j.equals(i) && (j.mask() & i.mask()) == i.mask() && j.covers(i.value())) { maximal = false; break; }
                if (maximal) expected.add(i);
            }
            assertEquals(expected, new HashSet<>(primes), f.toString());
            assertEquals(primes.size(), new HashSet<>(primes).size(), "no duplicates");
            List<Implicant> sorted = new ArrayList<>(primes);
            Collections.sort(sorted);
            assertEquals(sorted, primes, "canonical order");
        }
    }

    // Random dense 8-variable functions have large cyclic cores (exact cover is NP-hard): with a 2000-node budget
    // seeds 0,1,2,5 of this generator solve in milliseconds while seeds 3,4 (about 200 minterms) exhaust the budget.
    @Test
    void randomEightVariableFunctionsSolveOrReportBudget() {
        Random r = new Random(9);
        long start = System.nanoTime();
        int solved = 0, exhausted = 0;
        for (int t = 0; t < 6; t++) {
            Fn f = Fn.random(r, 8, t % 2 == 0);
            try {
                Sop s = Minimizer.minimize(8, f.on, f.dc, 2_000);
                assertValid(f, s);
                assertTrue(s.termCount() <= f.on.size());
                solved++;
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("budget"), e.getMessage());
                exhausted++;
            }
        }
        assertEquals(6, solved + exhausted);
        assertTrue(solved >= 4, "solved " + solved);
        assertTrue(exhausted >= 1, "exhausted " + exhausted);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 30_000, "took " + ms + " ms");
    }

    // 10 variables, f = AB + CDE + F'G + H'IJ'. Many primes (consensus terms), but the optimum has at most 4 terms
    // and 2+3+2+3 = 10 literals; the minimiser may find nothing cheaper than the generating expression only if it is
    // already optimal, so assert "no worse than" and exact validity.
    @Test
    void structuredTenVariableFunction() {
        Sop gen = Sop.parse(10, "AB + CDE + F'G + H'IJ'");
        List<Integer> on = new ArrayList<>();
        for (int m = 0; m < 1024; m++) if (gen.eval(m)) on.add(m);
        long start = System.nanoTime();
        Sop s = Minimizer.minimize(10, on, l());
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 20_000, "took " + ms + " ms");
        for (int m = 0; m < 1024; m++) assertEquals(gen.eval(m), s.eval(m), "m=" + m);
        assertEquals(4, s.termCount());
        assertEquals(10, s.literalCount(10));
    }

    @Test
    void hardRandomFunctionReportsBudgetInsteadOfHanging() {
        // dense random 10-variable functions have huge cyclic cores; a small budget must fail fast and cleanly
        Random r = new Random(3);
        Fn f = Fn.random(r, 10, true);
        long start = System.nanoTime();
        try {
            Sop s = Minimizer.minimize(10, f.on, f.dc, 50);
            assertValid(f, s); // allowed: solved within budget
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("budget"), e.getMessage());
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 20_000);
    }

    @Test
    void wideFunctionIsHandled() {
        // n=12: f = A (top bit set) -> single prime with one literal. (QM enumerates all 3^11 sub-cubes, so 16 is slow.)
        List<Integer> on = new ArrayList<>();
        for (int m = 1 << 11; m < (1 << 12); m++) on.add(m);
        assertEquals("A", Minimizer.minimize(12, on, l()).toString());
    }
}
