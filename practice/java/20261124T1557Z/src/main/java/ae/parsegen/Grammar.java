package ae.parsegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A context-free grammar. Text format: one rule per line, {@code Lhs -> alt | alt}; {@code #} starts a comment
 * line; {@code eps} or {@code ε} is the empty alternative. The first left-hand side is the start symbol and every
 * symbol without a rule is a terminal. Production ids follow textual order.
 */
public final class Grammar {
    public static final String END = "$";
    public static final String EPSILON = "ε";

    private final List<Production> productions;
    private final String start;
    private final Set<String> nonterminals;
    private final Set<String> terminals;

    private Grammar(List<Production> productions, Set<String> nonterminals) {
        this.productions = List.copyOf(productions);
        this.start = productions.get(0).lhs();
        this.nonterminals = Collections.unmodifiableSet(nonterminals);
        TreeSet<String> ts = new TreeSet<>();
        for (Production p : productions) {
            for (String s : p.rhs()) {
                if (!nonterminals.contains(s)) {
                    ts.add(s);
                }
            }
        }
        this.terminals = Collections.unmodifiableSet(ts);
    }

    public static Grammar parse(String text) {
        List<Production> prods = new ArrayList<>();
        Set<String> nts = new LinkedHashSet<>();
        String[] lines = text.split("\n", -1);
        for (int ln = 0; ln < lines.length; ln++) {
            String line = lines[ln].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String where = "line " + (ln + 1) + ": ";
            String[] tok = line.split("\\s+");
            int arrow = -1;
            for (int i = 0; i < tok.length; i++) {
                if (tok[i].equals("->")) {
                    arrow = i;
                    break;
                }
            }
            if (arrow < 0) {
                throw new IllegalArgumentException(where + "missing '->'");
            }
            if (arrow != 1) {
                throw new IllegalArgumentException(where + "left-hand side must be a single symbol");
            }
            String lhs = tok[0];
            checkSymbol(where, lhs);
            nts.add(lhs);
            List<String> alt = new ArrayList<>();
            for (int i = arrow + 1; i <= tok.length; i++) {
                if (i == tok.length || tok[i].equals("|")) {
                    prods.add(new Production(prods.size(), lhs, finishAlt(where, alt)));
                    alt.clear();
                } else {
                    checkSymbol(where, tok[i]);
                    alt.add(tok[i]);
                }
            }
        }
        if (prods.isEmpty()) {
            throw new IllegalArgumentException("no rules");
        }
        Grammar g = new Grammar(prods, nts);
        g.checkProductive();
        return g;
    }

    private static List<String> finishAlt(String where, List<String> alt) {
        if (alt.isEmpty()) {
            throw new IllegalArgumentException(where + "empty alternative (write eps)");
        }
        boolean hasEps = alt.contains("eps") || alt.contains(EPSILON);
        if (hasEps && alt.size() > 1) {
            throw new IllegalArgumentException(where + "epsilon must stand alone in its alternative");
        }
        return hasEps ? List.of() : new ArrayList<>(alt);
    }

    private static void checkSymbol(String where, String s) {
        if (s.startsWith("$")) {
            throw new IllegalArgumentException(where + "reserved symbol " + s);
        }
    }

    private void checkProductive() {
        Set<String> productive = new TreeSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : productions) {
                if (!productive.contains(p.lhs()) && p.rhs().stream().allMatch(s -> !isNonterminal(s) || productive.contains(s))) {
                    productive.add(p.lhs());
                    changed = true;
                }
            }
        }
        for (String nt : nonterminals) {
            if (!productive.contains(nt)) {
                throw new IllegalArgumentException("unproductive nonterminal " + nt);
            }
        }
    }

    public String start() {
        return start;
    }

    public List<Production> productions() {
        return productions;
    }

    public Production production(int id) {
        return productions.get(id);
    }

    public List<Production> productionsOf(String nt) {
        List<Production> out = new ArrayList<>();
        for (Production p : productions) {
            if (p.lhs().equals(nt)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Nonterminals in order of first appearance as a left-hand side. */
    public Set<String> nonterminals() {
        return nonterminals;
    }

    /** Terminals in natural string order. */
    public Set<String> terminals() {
        return terminals;
    }

    public boolean isNonterminal(String s) {
        return nonterminals.contains(s);
    }
}
