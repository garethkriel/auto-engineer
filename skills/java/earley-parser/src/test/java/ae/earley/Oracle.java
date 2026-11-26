package ae.earley;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Independent brute-force oracle: derivability by naive fixpoint over all spans, then a
 * derivation graph over derivable (nonterminal, i, j) nodes for cycle detection and counting.
 * It shares only the Grammar data model with the parser, never the Earley algorithm.
 */
final class Oracle {
    private final Grammar g;
    private final int[] in;
    private final int n;
    private final Map<String, Integer> id = new HashMap<>();
    private final boolean[][][] der;
    private final Map<Long, BigInteger> memo = new HashMap<>();
    private final Set<Long> grey = new HashSet<>();
    private boolean infinite;

    Oracle(Grammar g, String text) {
        this.g = g;
        this.in = text.codePoints().toArray();
        this.n = in.length;
        for (String nt : g.nonTerminals()) {
            id.put(nt, id.size());
        }
        der = new boolean[id.size()][n + 1][n + 1];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Rule r : g.rules()) {
                int a = id.get(r.lhs());
                for (int i = 0; i <= n; i++) {
                    for (int j = i; j <= n; j++) {
                        if (!der[a][i][j] && seq(r.rhs(), 0, i, j)) {
                            der[a][i][j] = true;
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    private boolean seq(List<Symbol> rhs, int k, int pos, int end) {
        if (k == rhs.size()) {
            return pos == end;
        }
        Symbol s = rhs.get(k);
        if (s instanceof Symbol.Terminal t) {
            return pos < end && in[pos] == t.codePoint() && seq(rhs, k + 1, pos + 1, end);
        }
        int nt = id.get(((Symbol.NonTerminal) s).name());
        for (int e = pos; e <= end; e++) {
            if (der[nt][pos][e] && seq(rhs, k + 1, e, end)) {
                return true;
            }
        }
        return false;
    }

    boolean accepts() {
        return der[id.get(g.start())][0][n];
    }

    /** Number of derivation trees, empty if infinite. */
    Optional<BigInteger> count() {
        if (!accepts()) {
            return Optional.of(BigInteger.ZERO);
        }
        infinite = false;
        memo.clear();
        grey.clear();
        BigInteger c = node(id.get(g.start()), 0, n);
        return infinite ? Optional.empty() : Optional.of(c);
    }

    private BigInteger node(int nt, int i, int j) {
        long key = ((long) nt * (n + 1) + i) * (n + 1) + j;
        BigInteger cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        if (!grey.add(key)) {
            infinite = true;
            return BigInteger.ZERO;
        }
        BigInteger total = BigInteger.ZERO;
        for (Rule r : g.rules()) {
            if (id.get(r.lhs()) == nt) {
                total = total.add(seqCount(r.rhs(), 0, i, j));
            }
        }
        grey.remove(key);
        memo.put(key, total);
        return total;
    }

    /** Counts only splits whose every part is derivable; others are skipped before recursing. */
    private BigInteger seqCount(List<Symbol> rhs, int k, int pos, int end) {
        if (k == rhs.size()) {
            return pos == end ? BigInteger.ONE : BigInteger.ZERO;
        }
        Symbol s = rhs.get(k);
        if (s instanceof Symbol.Terminal t) {
            if (pos < end && in[pos] == t.codePoint()) {
                return seqCount(rhs, k + 1, pos + 1, end);
            }
            return BigInteger.ZERO;
        }
        int nt = id.get(((Symbol.NonTerminal) s).name());
        BigInteger total = BigInteger.ZERO;
        for (int e = pos; e <= end; e++) {
            if (der[nt][pos][e] && seq(rhs, k + 1, e, end)) {
                total = total.add(node(nt, pos, e).multiply(seqCount(rhs, k + 1, e, end)));
            }
        }
        return total;
    }

    /** Structural check that a tree is a valid derivation of the grammar covering the whole input. */
    static List<String> violations(Grammar g, Tree t, String input) {
        List<String> out = new ArrayList<>();
        check(g, t, out);
        if (!t.yield().equals(input)) {
            out.add("yield " + t.yield() + " != " + input);
        }
        return out;
    }

    private static void check(Grammar g, Tree t, List<String> out) {
        if (!(t instanceof Tree.Node node)) {
            return;
        }
        Rule r = g.rules().get(node.rule());
        if (!r.lhs().equals(node.symbol()) || r.rhs().size() != node.children().size()) {
            out.add("node " + node.symbol() + " does not match rule " + r);
            return;
        }
        for (int k = 0; k < r.rhs().size(); k++) {
            Tree child = node.children().get(k);
            Symbol s = r.rhs().get(k);
            if (s instanceof Symbol.Terminal term) {
                if (!(child instanceof Tree.Leaf leaf) || leaf.codePoint() != term.codePoint()) {
                    out.add("child " + k + " of " + r + " is not leaf " + term);
                }
            } else if (!(child instanceof Tree.Node cn) || !cn.symbol().equals(((Symbol.NonTerminal) s).name())) {
                out.add("child " + k + " of " + r + " has wrong symbol");
            } else {
                check(g, child, out);
            }
        }
    }
}
