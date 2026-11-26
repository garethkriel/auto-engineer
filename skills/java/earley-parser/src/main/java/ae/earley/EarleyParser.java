package ae.earley;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Earley recogniser for any context-free grammar (left/right recursive, ambiguous, nullable,
 * cyclic). Nullable symbols are handled by advancing past them in the predictor
 * (Aycock-Horspool), so completion never has to revisit an item set.
 */
public final class EarleyParser {
    private static final int MAX_INPUT = (1 << 20) - 1;

    private final Grammar grammar;
    private final int[][] rhsNt;      // per rule: nonterminal id of each symbol, or -1 for a terminal
    private final int[][] rhsTerm;    // per rule: terminal code point (valid when rhsNt == -1)
    private final int[] lhsId;
    private final int[][] rulesOf;    // per nonterminal id: rule indexes
    private final boolean[] nullable; // per nonterminal id
    private final int startId;
    private final Map<String, Integer> ids = new HashMap<>();

    public EarleyParser(Grammar grammar) {
        this.grammar = Objects.requireNonNull(grammar, "grammar");
        for (String nt : grammar.nonTerminals()) {
            ids.put(nt, ids.size());
        }
        List<Rule> rules = grammar.rules();
        rhsNt = new int[rules.size()][];
        rhsTerm = new int[rules.size()][];
        lhsId = new int[rules.size()];
        for (int r = 0; r < rules.size(); r++) {
            Rule rule = rules.get(r);
            lhsId[r] = ids.get(rule.lhs());
            int m = rule.rhs().size();
            rhsNt[r] = new int[m];
            rhsTerm[r] = new int[m];
            for (int k = 0; k < m; k++) {
                if (rule.rhs().get(k) instanceof Symbol.NonTerminal nt) {
                    rhsNt[r][k] = ids.get(nt.name());
                } else {
                    rhsNt[r][k] = -1;
                    rhsTerm[r][k] = ((Symbol.Terminal) rule.rhs().get(k)).codePoint();
                }
            }
        }
        rulesOf = new int[ids.size()][];
        for (String nt : grammar.nonTerminals()) {
            rulesOf[ids.get(nt)] = grammar.ruleIndexes(nt).stream().mapToInt(Integer::intValue).toArray();
        }
        nullable = new boolean[ids.size()];
        for (String nt : grammar.nullable()) {
            nullable[ids.get(nt)] = true;
        }
        startId = ids.get(grammar.start());
    }

    /** One Earley item set: insertion-ordered items plus an index of items waiting on a nonterminal. */
    private static final class ItemSet {
        final ArrayList<Long> items = new ArrayList<>();
        final HashSet<Long> seen = new HashSet<>();
        final Map<Integer, List<Long>> waiting = new HashMap<>();
    }

    private static long item(int rule, int dot, int origin) {
        return ((long) rule << 40) | ((long) dot << 20) | origin;
    }

    private static int ruleOf(long it) {
        return (int) (it >>> 40);
    }

    private static int dotOf(long it) {
        return (int) ((it >>> 20) & 0xFFFFF);
    }

    private static int originOf(long it) {
        return (int) (it & 0xFFFFF);
    }

    private void add(ItemSet set, long it) {
        if (!set.seen.add(it)) {
            return;
        }
        set.items.add(it);
        int r = ruleOf(it);
        int d = dotOf(it);
        if (d < rhsNt[r].length && rhsNt[r][d] >= 0) {
            set.waiting.computeIfAbsent(rhsNt[r][d], x -> new ArrayList<>()).add(it);
        }
    }

    /** Parses {@code input} (as code points) and returns the chart-derived result. */
    public ParseResult parse(String input) {
        Objects.requireNonNull(input, "input");
        int[] in = input.codePoints().toArray();
        int n = in.length;
        if (n > MAX_INPUT) {
            throw new IllegalArgumentException("input too long: " + n);
        }
        ItemSet[] sets = new ItemSet[n + 1];
        for (int k = 0; k <= n; k++) {
            sets[k] = new ItemSet();
        }
        for (int r : rulesOf[startId]) {
            add(sets[0], item(r, 0, 0));
        }
        Map<Long, TreeSet<Integer>> complete = new HashMap<>();
        int lastNonEmpty = 0;
        for (int k = 0; k <= n; k++) {
            ItemSet cur = sets[k];
            if (cur.items.isEmpty()) {
                break;
            }
            lastNonEmpty = k;
            for (int idx = 0; idx < cur.items.size(); idx++) {
                long it = cur.items.get(idx);
                int r = ruleOf(it);
                int d = dotOf(it);
                int o = originOf(it);
                if (d == rhsNt[r].length) {
                    complete.computeIfAbsent(spanKey(lhsId[r], o, k), x -> new TreeSet<>()).add(r);
                    List<Long> parents = sets[o].waiting.get(lhsId[r]);
                    if (parents != null) {
                        // index loop: when o == k the list may grow while we iterate
                        for (int p = 0; p < parents.size(); p++) {
                            long w = parents.get(p);
                            add(cur, item(ruleOf(w), dotOf(w) + 1, originOf(w)));
                        }
                    }
                } else if (rhsNt[r][d] >= 0) {
                    int nt = rhsNt[r][d];
                    for (int pr : rulesOf[nt]) {
                        add(cur, item(pr, 0, k));
                    }
                    if (nullable[nt]) {
                        add(cur, item(r, d + 1, o));
                    }
                } else if (k < n && in[k] == rhsTerm[r][d]) {
                    add(sets[k + 1], item(r, d + 1, o));
                }
            }
        }
        boolean accepted = complete.containsKey(spanKey(startId, 0, n));
        int errorOffset = -1;
        TreeSet<Integer> expected = new TreeSet<>();
        if (!accepted) {
            errorOffset = lastNonEmpty;
            for (long it : sets[errorOffset].items) {
                int r = ruleOf(it);
                int d = dotOf(it);
                if (d < rhsNt[r].length && rhsNt[r][d] < 0) {
                    expected.add(rhsTerm[r][d]);
                }
            }
        }
        int total = 0;
        for (ItemSet s : sets) {
            total += s.items.size();
        }
        return new ParseResult(this, in, accepted, errorOffset, expected, total, complete);
    }

    static long spanKey(int nt, int i, int j) {
        return ((long) nt << 42) | ((long) i << 21) | j;
    }

    // --- accessors used by ParseResult -------------------------------------------------------

    Grammar grammar() {
        return grammar;
    }

    int[][] rhsNt() {
        return rhsNt;
    }

    int[][] rhsTerm() {
        return rhsTerm;
    }

    int[] lhsIds() {
        return lhsId;
    }

    int startId() {
        return startId;
    }
}
