package ae.qmc;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Exact two-level boolean minimisation (Quine–McCluskey prime generation plus exact set cover).
 * Minimises the number of terms first and the number of literals second.
 */
public final class Minimizer {
    public static final int MAX_VARS = 16;
    public static final long DEFAULT_BUDGET = 2_000_000;

    private Minimizer() {}

    public static List<Implicant> primeImplicants(int n, Collection<Integer> minterms, Collection<Integer> dontCares) {
        TreeSet<Integer> points = validate(n, minterms, dontCares);
        List<Implicant> primes = new ArrayList<>();
        // level: mask -> set of values (all implicants of one level share the number of masked bits)
        TreeMap<Integer, TreeSet<Integer>> level = new TreeMap<>();
        level.computeIfAbsent(0, k -> new TreeSet<>()).addAll(points);
        while (!level.isEmpty()) {
            TreeMap<Integer, TreeSet<Integer>> next = new TreeMap<>();
            for (var e : level.entrySet()) {
                int mask = e.getKey();
                TreeSet<Integer> values = e.getValue();
                Set<Integer> merged = new HashSet<>();
                for (int v : values) {
                    for (int b = 0; b < n; b++) {
                        int bit = 1 << b;
                        if ((mask & bit) != 0 || (v & bit) != 0) continue; // visit each pair once, from its 0 side
                        if (values.contains(v | bit)) {
                            merged.add(v);
                            merged.add(v | bit);
                            next.computeIfAbsent(mask | bit, k -> new TreeSet<>()).add(v);
                        }
                    }
                }
                for (int v : values) if (!merged.contains(v)) primes.add(new Implicant(v, mask));
            }
            level = next;
        }
        primes.sort(null);
        return primes;
    }

    public static Sop minimize(int n, Collection<Integer> minterms, Collection<Integer> dontCares) {
        return minimize(n, minterms, dontCares, DEFAULT_BUDGET);
    }

    /**
     * @param maxNodes limit on branching calls of the exact cover search; exceeding it throws
     *                 {@link IllegalStateException}. Zero means "essential primes must suffice".
     */
    public static Sop minimize(int n, Collection<Integer> minterms, Collection<Integer> dontCares, long maxNodes) {
        if (maxNodes < 0) throw new IllegalArgumentException("negative budget");
        TreeSet<Integer> points = validate(n, minterms, dontCares);
        List<Integer> care = new ArrayList<>(new TreeSet<>(minterms));
        if (care.isEmpty()) return new Sop(n, List.of());

        List<Implicant> candidates = new ArrayList<>();
        List<BitSet> cover = new ArrayList<>();
        for (Implicant p : primeImplicants(n, minterms, dontCares)) {
            BitSet bs = new BitSet(care.size());
            for (int i = 0; i < care.size(); i++) if (p.covers(care.get(i))) bs.set(i);
            if (!bs.isEmpty()) {
                candidates.add(p);
                cover.add(bs);
            }
        }
        Search search = new Search(n, candidates, cover, maxNodes);
        BitSet all = new BitSet(care.size());
        all.set(0, care.size());
        search.solve(all, new ArrayList<>(), new boolean[candidates.size()], 0, 0);
        List<Implicant> result = new ArrayList<>();
        for (int idx : search.best) result.add(candidates.get(idx));
        assert points.size() >= care.size();
        return new Sop(n, result);
    }

    private static TreeSet<Integer> validate(int n, Collection<Integer> minterms, Collection<Integer> dontCares) {
        Objects.requireNonNull(minterms, "minterms");
        Objects.requireNonNull(dontCares, "dontCares");
        if (n < 1 || n > MAX_VARS) throw new IllegalArgumentException("n out of range: " + n);
        int limit = 1 << n;
        TreeSet<Integer> points = new TreeSet<>();
        for (int m : minterms) {
            if (m < 0 || m >= limit) throw new IllegalArgumentException("minterm out of range: " + m);
            if (!points.add(m)) throw new IllegalArgumentException("duplicate or shared term: " + m);
        }
        for (int d : dontCares) {
            if (d < 0 || d >= limit) throw new IllegalArgumentException("don't-care out of range: " + d);
            if (!points.add(d)) throw new IllegalArgumentException("duplicate or shared term: " + d);
        }
        return points;
    }

    /** Exact branch-and-bound cover; cost is (terms, literals), compared lexicographically. */
    private static final class Search {
        final int n;
        final List<Implicant> cands;
        final List<BitSet> cover;
        final long maxNodes;
        long nodes;
        List<Integer> best;
        int bestTerms = Integer.MAX_VALUE, bestLits = Integer.MAX_VALUE;

        Search(int n, List<Implicant> cands, List<BitSet> cover, long maxNodes) {
            this.n = n;
            this.cands = cands;
            this.cover = cover;
            this.maxNodes = maxNodes;
        }

        /** Dominance passes cost O(M^2) / O(C^2); skip them on huge instances (they are only optimisations). */
        static final int DOMINANCE_CAP = 1500;

        void solve(BitSet required, List<Integer> chosen, boolean[] inheritedDead, int terms, int lits) {
            BitSet uncovered = (BitSet) required.clone();
            chosen = new ArrayList<>(chosen);
            // Candidates removed by column dominance or by an earlier sibling branch. Must be inherited: rows dropped
            // by row dominance are only implied under the candidate set they were dropped with, so a child that
            // resurrected a dead prime could cover a kept row without covering the dropped one.
            boolean[] dead = inheritedDead.clone();
            BitSet[] reach = new BitSet[cands.size()];  // cover restricted to uncovered minterms (live only)
            BitSet[] candBits = new BitSet[cover.get(0).size() + 1]; // minterm -> live candidates covering it
            while (!uncovered.isEmpty()) {
                for (int m = uncovered.nextSetBit(0); m >= 0; m = uncovered.nextSetBit(m + 1)) candBits[m] = new BitSet(cands.size());
                for (int c = 0; c < cands.size(); c++) {
                    if (dead[c]) continue;
                    reach[c] = (BitSet) cover.get(c).clone();
                    reach[c].and(uncovered);
                    for (int m = reach[c].nextSetBit(0); m >= 0; m = reach[c].nextSetBit(m + 1)) candBits[m].set(c);
                }
                boolean changed = false;
                // essential primes: one pass; picks never change another minterm's live candidate set
                BitSet picks = new BitSet(cands.size()); // a set: two minterms may share the same only candidate
                for (int m = uncovered.nextSetBit(0); m >= 0; m = uncovered.nextSetBit(m + 1)) {
                    if (candBits[m].cardinality() == 1) picks.set(candBits[m].nextSetBit(0));
                }
                if (!picks.isEmpty()) {
                    for (int c = picks.nextSetBit(0); c >= 0; c = picks.nextSetBit(c + 1)) {
                        chosen.add(c); // never already chosen: a chosen prime has an empty reach
                        uncovered.andNot(cover.get(c));
                    }
                    continue;
                }
                // row dominance: if every candidate of m2 also covers m1, covering m2 covers m1, so m1 can be ignored
                if (uncovered.cardinality() <= DOMINANCE_CAP) {
                    for (int m1 = uncovered.nextSetBit(0); m1 >= 0; m1 = uncovered.nextSetBit(m1 + 1)) {
                        for (int m2 = uncovered.nextSetBit(0); m2 >= 0; m2 = uncovered.nextSetBit(m2 + 1)) {
                            if (m1 == m2) continue;
                            BitSet extra = (BitSet) candBits[m2].clone();
                            extra.andNot(candBits[m1]);
                            if (!extra.isEmpty()) continue;
                            if (candBits[m1].equals(candBits[m2]) && m2 > m1) continue; // equal rows: lower index survives
                            uncovered.clear(m1);
                            changed = true;
                            break;
                        }
                    }
                }
                // column dominance: c1 is useless if c2 reaches a superset of it for no more literals
                int live = 0;
                for (int c = 0; c < cands.size(); c++) if (!dead[c] && !reach[c].isEmpty()) live++;
                if (live <= DOMINANCE_CAP) {
                    for (int c1 = 0; c1 < cands.size(); c1++) {
                        if (dead[c1] || reach[c1].isEmpty()) continue;
                        for (int c2 = 0; c2 < cands.size(); c2++) {
                            if (c1 == c2 || dead[c2] || reach[c2].isEmpty()) continue;
                            int l1 = cands.get(c1).literals(n), l2 = cands.get(c2).literals(n);
                            if (l2 > l1) continue;
                            BitSet diff = (BitSet) reach[c1].clone();
                            diff.andNot(reach[c2]);
                            if (!diff.isEmpty()) continue;
                            if (l1 == l2 && reach[c1].equals(reach[c2]) && c2 > c1) continue; // identical: lower index survives
                            dead[c1] = true;
                            changed = true;
                            break;
                        }
                    }
                }
                if (!changed) break;
                // rows ignored by dominance are not dropped from "required" for the final check below
            }
            terms = chosen.size();
            lits = 0;
            for (int c : chosen) lits += cands.get(c).literals(n);
            if (uncovered.isEmpty()) {
                if (terms < bestTerms || (terms == bestTerms && lits < bestLits)) {
                    bestTerms = terms;
                    bestLits = lits;
                    List<Integer> sorted = new ArrayList<>(chosen);
                    sorted.sort(null);
                    best = sorted;
                }
                return;
            }
            // lower bound: minterms with pairwise disjoint candidate sets each need their own term
            BitSet used = new BitSet(cands.size());
            int need = 0;
            for (int m = uncovered.nextSetBit(0); m >= 0; m = uncovered.nextSetBit(m + 1)) {
                if (!candBits[m].intersects(used)) {
                    used.or(candBits[m]);
                    need++;
                }
            }
            if (terms + need > bestTerms || (terms + need == bestTerms && lits >= bestLits)) return;
            if (++nodes > maxNodes) throw new IllegalStateException("search budget exceeded: " + maxNodes + " nodes");
            // branch on the uncovered minterm with the fewest live candidate primes
            int target = -1, fewest = Integer.MAX_VALUE;
            for (int m = uncovered.nextSetBit(0); m >= 0; m = uncovered.nextSetBit(m + 1)) {
                int count = candBits[m].cardinality();
                if (count < fewest) { fewest = count; target = m; }
            }
            List<Integer> options = new ArrayList<>();
            for (int c = candBits[target].nextSetBit(0); c >= 0; c = candBits[target].nextSetBit(c + 1)) options.add(c);
            final BitSet[] r = reach;
            options.sort((x, y) -> {
                int d = Integer.compare(r[y].cardinality(), r[x].cardinality());
                return d != 0 ? d : Integer.compare(x, y);
            });
            for (int c : options) {
                BitSet rest = (BitSet) uncovered.clone();
                rest.andNot(cover.get(c));
                List<Integer> next = new ArrayList<>(chosen);
                next.add(c);
                solve(rest, next, dead, terms + 1, lits + cands.get(c).literals(n));
                dead[c] = true; // every solution containing c was explored: later siblings may skip it
            }
        }
    }
}
