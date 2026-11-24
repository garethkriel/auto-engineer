package ae.parsegen;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Nullable, FIRST and FOLLOW sets of a grammar, computed by fixpoint iteration. */
public final class Analysis {
    private final Grammar g;
    private final Set<String> nullable = new HashSet<>();
    private final Map<String, Set<String>> first = new HashMap<>();
    private final Map<String, Set<String>> follow = new HashMap<>();

    public Analysis(Grammar g) {
        this.g = g;
        computeNullable();
        computeFirst();
        computeFollow();
    }

    private void computeNullable() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : g.productions()) {
                if (!nullable.contains(p.lhs()) && nullableSeq(p.rhs())) {
                    nullable.add(p.lhs());
                    changed = true;
                }
            }
        }
    }

    private void computeFirst() {
        for (String nt : g.nonterminals()) {
            first.put(nt, new TreeSet<>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : g.productions()) {
                if (first.get(p.lhs()).addAll(firstSeq(p.rhs()))) {
                    changed = true;
                }
            }
        }
    }

    private void computeFollow() {
        for (String nt : g.nonterminals()) {
            follow.put(nt, new TreeSet<>());
        }
        follow.get(g.start()).add(Grammar.END);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : g.productions()) {
                List<String> rhs = p.rhs();
                for (int i = 0; i < rhs.size(); i++) {
                    String sym = rhs.get(i);
                    if (!g.isNonterminal(sym)) {
                        continue;
                    }
                    List<String> rest = rhs.subList(i + 1, rhs.size());
                    Set<String> target = follow.get(sym);
                    if (target.addAll(firstSeq(rest))) {
                        changed = true;
                    }
                    if (nullableSeq(rest) && target.addAll(follow.get(p.lhs()))) {
                        changed = true;
                    }
                }
            }
        }
    }

    public Set<String> nullable() {
        return Collections.unmodifiableSet(nullable);
    }

    public boolean isNullable(String symbol) {
        return nullable.contains(symbol);
    }

    public boolean nullableSeq(List<String> seq) {
        for (String s : seq) {
            if (!nullable.contains(s)) {
                return false;
            }
        }
        return true;
    }

    /** FIRST of one symbol (terminals only; epsilon is reported by {@link #isNullable}). */
    public Set<String> first(String symbol) {
        Set<String> f = first.get(symbol);
        return f == null ? Set.of(symbol) : Collections.unmodifiableSet(f);
    }

    /** FIRST of a symbol sequence, without epsilon. */
    public Set<String> firstSeq(List<String> seq) {
        Set<String> out = new TreeSet<>();
        for (String s : seq) {
            Set<String> f = first.get(s);
            if (f == null) {
                out.add(s);
                return out;
            }
            out.addAll(f);
            if (!nullable.contains(s)) {
                return out;
            }
        }
        return out;
    }

    /** FOLLOW of a nonterminal; {@code $} marks end of input. */
    public Set<String> follow(String nonterminal) {
        Set<String> f = follow.get(nonterminal);
        if (f == null) {
            throw new IllegalArgumentException("not a nonterminal: " + nonterminal);
        }
        return Collections.unmodifiableSet(f);
    }
}
