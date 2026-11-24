package ae.parsegen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Earley recogniser, used as an independent oracle for the table-driven parsers. */
public final class Earley {
    private record Item(int prod, int dot, int origin) {}

    private Earley() {}

    public static boolean recognizes(Grammar g, List<String> tokens) {
        return firstError(g, tokens) < 0;
    }

    /**
     * Index of the first token that cannot extend any viable prefix, {@code tokens.size()} when every prefix is
     * viable but the input is incomplete, or -1 when the input is a sentence.
     */
    public static int firstError(Grammar g, List<String> tokens) {
        Analysis an = new Analysis(g);
        int n = tokens.size();
        List<List<Item>> chart = new ArrayList<>();
        List<Set<Item>> seen = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            chart.add(new ArrayList<>());
            seen.add(new HashSet<>());
        }
        for (Production p : g.productionsOf(g.start())) {
            add(chart, seen, 0, new Item(p.id(), 0, 0));
        }
        for (int i = 0; i <= n; i++) {
            List<Item> set = chart.get(i);
            for (int k = 0; k < set.size(); k++) {
                Item it = set.get(k);
                Production p = g.production(it.prod());
                if (it.dot() < p.rhs().size()) {
                    String sym = p.rhs().get(it.dot());
                    if (g.isNonterminal(sym)) {
                        for (Production q : g.productionsOf(sym)) {
                            add(chart, seen, i, new Item(q.id(), 0, i));
                        }
                        if (an.isNullable(sym)) {
                            add(chart, seen, i, new Item(it.prod(), it.dot() + 1, it.origin()));
                        }
                    } else if (i < n && tokens.get(i).equals(sym)) {
                        add(chart, seen, i + 1, new Item(it.prod(), it.dot() + 1, it.origin()));
                    }
                } else {
                    List<Item> parents = chart.get(it.origin());
                    for (int j = 0; j < parents.size(); j++) {
                        Item par = parents.get(j);
                        Production pp = g.production(par.prod());
                        if (par.dot() < pp.rhs().size() && pp.rhs().get(par.dot()).equals(p.lhs())) {
                            add(chart, seen, i, new Item(par.prod(), par.dot() + 1, par.origin()));
                        }
                    }
                }
            }
            if (i < n && chart.get(i + 1).isEmpty()) {
                return i;
            }
        }
        for (Item it : chart.get(n)) {
            Production p = g.production(it.prod());
            if (it.origin() == 0 && it.dot() == p.rhs().size() && p.lhs().equals(g.start())) {
                return -1;
            }
        }
        return n;
    }

    private static void add(List<List<Item>> chart, List<Set<Item>> seen, int i, Item it) {
        if (seen.get(i).add(it)) {
            chart.get(i).add(it);
        }
    }
}
