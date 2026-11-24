package ae.parsegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Sentence generators used to test parsers. */
public final class Generator {
    private Generator() {}

    /**
     * All sentences of at most {@code maxLen} tokens, ordered by length then lexicographically. Only for
     * epsilon-free grammars, where every symbol yields at least one token so long forms can be pruned.
     */
    public static Set<List<String>> enumerate(Grammar g, int maxLen) {
        for (Production p : g.productions()) {
            if (p.rhs().isEmpty()) {
                throw new IllegalArgumentException("enumerate needs an epsilon-free grammar: " + p);
            }
        }
        Set<List<String>> sentences = new HashSet<>();
        Set<List<String>> visited = new HashSet<>();
        Deque<List<String>> work = new ArrayDeque<>();
        List<String> init = List.of(g.start());
        visited.add(init);
        work.add(init);
        while (!work.isEmpty()) {
            List<String> form = work.poll();
            int at = -1;
            for (int i = 0; i < form.size(); i++) {
                if (g.isNonterminal(form.get(i))) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                sentences.add(form);
                continue;
            }
            for (Production p : g.productionsOf(form.get(at))) {
                List<String> next = new ArrayList<>(form.subList(0, at));
                next.addAll(p.rhs());
                next.addAll(form.subList(at + 1, form.size()));
                if (next.size() <= maxLen && visited.add(next)) {
                    work.add(next);
                }
            }
        }
        List<List<String>> sorted = new ArrayList<>(sentences);
        sorted.sort(Comparator.<List<String>>comparingInt(List::size)
                .thenComparing(w -> String.join(" ", w)));
        return new LinkedHashSet<>(sorted);
    }

    /**
     * A random sentence. While {@code budget} derivation steps of depth remain alternatives are chosen at random;
     * past it, the production with the smallest derivation height is taken, so generation always terminates.
     */
    public static List<String> random(Grammar g, Random rnd, int budget) {
        Map<String, Integer> height = heights(g);
        List<String> out = new ArrayList<>();
        expand(g, g.start(), rnd, budget, height, out);
        return out;
    }

    private static Map<String, Integer> heights(Grammar g) {
        Map<String, Integer> h = new HashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : g.productions()) {
                int best = productionHeight(g, p, h);
                if (best >= 0 && (!h.containsKey(p.lhs()) || best < h.get(p.lhs()))) {
                    h.put(p.lhs(), best);
                    changed = true;
                }
            }
        }
        return h;
    }

    /** 1 + max height of the right-hand side's nonterminals, or -1 if one of them has no height yet. */
    private static int productionHeight(Grammar g, Production p, Map<String, Integer> h) {
        int max = 0;
        for (String s : p.rhs()) {
            if (g.isNonterminal(s)) {
                Integer x = h.get(s);
                if (x == null) {
                    return -1;
                }
                max = Math.max(max, x);
            }
        }
        return max + 1;
    }

    private static void expand(Grammar g, String sym, Random rnd, int budget, Map<String, Integer> height, List<String> out) {
        if (!g.isNonterminal(sym)) {
            out.add(sym);
            return;
        }
        List<Production> alts = g.productionsOf(sym);
        Production pick = null;
        if (budget > 0) {
            pick = alts.get(rnd.nextInt(alts.size()));
        } else {
            int best = Integer.MAX_VALUE;
            for (Production p : alts) {
                int ph = productionHeight(g, p, height);
                if (ph >= 0 && ph < best) {
                    best = ph;
                    pick = p;
                }
            }
        }
        for (String s : pick.rhs()) {
            expand(g, s, rnd, budget - 1, height, out);
        }
    }
}
