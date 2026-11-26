package ae.earley;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** Outcome of one {@link EarleyParser#parse}: verdict, error report, and the parse forest. */
public final class ParseResult {
    private final EarleyParser parser;
    private final int[] input;
    private final boolean accepted;
    private final int errorOffset;
    private final SortedSet<Integer> expected;
    private final int chartItems;
    private final Map<Long, TreeSet<Integer>> complete;

    ParseResult(EarleyParser parser, int[] input, boolean accepted, int errorOffset,
                SortedSet<Integer> expected, int chartItems, Map<Long, TreeSet<Integer>> complete) {
        this.parser = parser;
        this.input = input;
        this.accepted = accepted;
        this.errorOffset = errorOffset;
        this.expected = Collections.unmodifiableSortedSet(expected);
        this.chartItems = chartItems;
        this.complete = complete;
    }

    public boolean accepted() {
        return accepted;
    }

    /** Code-point index of the first unconsumable symbol, or -1 when accepted. */
    public int errorOffset() {
        return errorOffset;
    }

    /** Code points that could have been consumed at {@link #errorOffset()}, ascending. */
    public Set<Integer> expected() {
        return expected;
    }

    /** Total number of distinct Earley items over all item sets. */
    public int chartItems() {
        return chartItems;
    }

    // --- shared span helpers -----------------------------------------------------------------

    private boolean valid(int rule, int k, int pos, int end) {
        int nt = parser.rhsNt()[rule][k];
        if (nt < 0) {
            return end == pos + 1 && pos < input.length && input[pos] == parser.rhsTerm()[rule][k];
        }
        return complete.containsKey(EarleyParser.spanKey(nt, pos, end));
    }

    /**
     * For rule {@code r} over span [i, j]: ok[k][p - i] is true when the first k symbols can cover
     * [i, p] and the remaining symbols can cover [p, j]. Only such positions lie on a real derivation.
     */
    private boolean[][] feasible(int r, int i, int j) {
        int m = parser.rhsNt()[r].length;
        int w = j - i + 1;
        boolean[][] fwd = new boolean[m + 1][w];
        boolean[][] bwd = new boolean[m + 1][w];
        fwd[0][0] = true;
        for (int k = 0; k < m; k++) {
            for (int p = 0; p < w; p++) {
                if (!fwd[k][p]) {
                    continue;
                }
                for (int e = i + p; e <= j; e++) {
                    if (valid(r, k, i + p, e)) {
                        fwd[k + 1][e - i] = true;
                    }
                }
            }
        }
        bwd[m][w - 1] = true;
        for (int k = m - 1; k >= 0; k--) {
            for (int p = 0; p < w; p++) {
                for (int e = i + p; e <= j; e++) {
                    if (bwd[k + 1][e - i] && valid(r, k, i + p, e)) {
                        bwd[k][p] = true;
                        break;
                    }
                }
            }
        }
        boolean[][] ok = new boolean[m + 1][w];
        for (int k = 0; k <= m; k++) {
            for (int p = 0; p < w; p++) {
                ok[k][p] = fwd[k][p] && bwd[k][p];
            }
        }
        return ok;
    }

    // --- counting ----------------------------------------------------------------------------

    private Map<Long, BigInteger> memo;
    private Set<Long> grey;
    private boolean infinite;

    /** Number of distinct parse trees; 0 if rejected; empty if infinitely many. */
    public Optional<BigInteger> treeCount() {
        if (!accepted) {
            return Optional.of(BigInteger.ZERO);
        }
        memo = new HashMap<>();
        grey = new HashSet<>();
        infinite = false;
        BigInteger c = countNode(parser.startId(), 0, input.length);
        Optional<BigInteger> result = infinite ? Optional.empty() : Optional.of(c);
        memo = null;
        grey = null;
        return result;
    }

    private BigInteger countNode(int nt, int i, int j) {
        long key = EarleyParser.spanKey(nt, i, j);
        BigInteger cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        if (!grey.add(key)) {
            infinite = true; // N =>+ N over the same span inside a real derivation
            return BigInteger.ZERO;
        }
        BigInteger total = BigInteger.ZERO;
        for (int r : complete.get(key)) {
            total = total.add(countSeq(r, i, j));
        }
        grey.remove(key);
        memo.put(key, total);
        return total;
    }

    private BigInteger countSeq(int r, int i, int j) {
        int m = parser.rhsNt()[r].length;
        boolean[][] ok = feasible(r, i, j);
        int w = j - i + 1;
        BigInteger[][] ways = new BigInteger[m + 1][w];
        for (BigInteger[] row : ways) {
            java.util.Arrays.fill(row, BigInteger.ZERO);
        }
        ways[0][0] = BigInteger.ONE;
        for (int k = 0; k < m; k++) {
            int nt = parser.rhsNt()[r][k];
            for (int p = 0; p < w; p++) {
                if (!ok[k][p] || ways[k][p].signum() == 0) {
                    continue;
                }
                for (int e = i + p; e <= j; e++) {
                    if (!ok[k + 1][e - i] || !valid(r, k, i + p, e)) {
                        continue;
                    }
                    BigInteger sub = nt < 0 ? BigInteger.ONE : countNode(nt, i + p, e);
                    ways[k + 1][e - i] = ways[k + 1][e - i].add(ways[k][p].multiply(sub));
                }
            }
        }
        return ways[m][w - 1];
    }

    // --- enumeration -------------------------------------------------------------------------

    /**
     * Up to {@code limit} distinct trees in deterministic order (see TASK.md). Trees that repeat
     * a nonterminal over the same span on one root-to-leaf path are excluded.
     */
    public List<Tree> trees(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit < 0");
        }
        if (!accepted || limit == 0) {
            return List.of();
        }
        return List.copyOf(nodeTrees(parser.startId(), 0, input.length, new HashSet<>(), limit));
    }

    private List<Tree> nodeTrees(int nt, int i, int j, Set<Long> path, int cap) {
        long key = EarleyParser.spanKey(nt, i, j);
        TreeSet<Integer> rules = complete.get(key);
        List<Tree> out = new ArrayList<>();
        if (rules == null || path.contains(key)) {
            return out;
        }
        path.add(key);
        String name = parser.grammar().rules().get(rules.first()).lhs();
        for (int r : rules) {
            if (out.size() >= cap) {
                break;
            }
            for (List<Tree> kids : seqTrees(r, 0, i, j, path, cap - out.size())) {
                out.add(new Tree.Node(name, r, kids));
            }
        }
        path.remove(key);
        return out;
    }

    private List<List<Tree>> seqTrees(int r, int k, int pos, int end, Set<Long> path, int cap) {
        List<List<Tree>> out = new ArrayList<>();
        int m = parser.rhsNt()[r].length;
        if (k == m) {
            if (pos == end) {
                out.add(List.of());
            }
            return out;
        }
        int nt = parser.rhsNt()[r][k];
        if (nt < 0) {
            if (valid(r, k, pos, pos + 1) && pos + 1 <= end) {
                for (List<Tree> rest : seqTrees(r, k + 1, pos + 1, end, path, cap)) {
                    out.add(prepend(new Tree.Leaf(input[pos]), rest));
                }
            }
            return out;
        }
        for (int e = pos; e <= end && out.size() < cap; e++) {
            if (!valid(r, k, pos, e)) {
                continue;
            }
            List<List<Tree>> rests = seqTrees(r, k + 1, e, end, path, cap);
            if (rests.isEmpty()) {
                continue;
            }
            for (Tree first : nodeTrees(nt, pos, e, path, cap)) {
                for (List<Tree> rest : rests) {
                    if (out.size() >= cap) {
                        return out;
                    }
                    out.add(prepend(first, rest));
                }
            }
        }
        return out;
    }

    private static List<Tree> prepend(Tree first, List<Tree> rest) {
        List<Tree> l = new ArrayList<>(rest.size() + 1);
        l.add(first);
        l.addAll(rest);
        return l;
    }
}
