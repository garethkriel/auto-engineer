package ae.lr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A context-free grammar with FIRST/FOLLOW analysis.
 *
 * <p>Text format: one rule per line, {@code A -> x y | z | ε}; {@code #} starts a comment line;
 * the first left-hand side is the start symbol; symbols that never occur on a left-hand side
 * are terminals. An empty alternative must be written {@code ε} (or {@code %empty}).
 */
public final class Grammar {

    /** End-of-input marker. */
    public static final String EOF = "$";
    /** Left-hand side of the augmented production 0. */
    public static final String ACCEPT = "$accept";

    private final List<Production> productions;
    private final String start;
    private final List<String> symbols;
    private final Set<String> nonterminals;
    private final Set<String> terminals;
    private final Set<String> nullable = new LinkedHashSet<>();
    private final Map<String, Set<String>> first = new LinkedHashMap<>();
    private final Map<String, Set<String>> follow = new LinkedHashMap<>();

    private Grammar(List<Production> userProductions, List<String> symbols, Set<String> nts) {
        this.start = userProductions.get(0).lhs();
        List<Production> all = new ArrayList<>();
        all.add(new Production(0, ACCEPT, List.of(start)));
        all.addAll(userProductions);
        this.productions = Collections.unmodifiableList(all);
        this.symbols = Collections.unmodifiableList(symbols);
        this.nonterminals = Collections.unmodifiableSet(nts);
        Set<String> ts = new LinkedHashSet<>();
        for (String s : symbols) {
            if (!nts.contains(s)) {
                ts.add(s);
            }
        }
        this.terminals = Collections.unmodifiableSet(ts);
        computeNullable();
        computeFirst();
        computeFollow();
    }

    /** Parses the text format described in the class comment. */
    public static Grammar parse(String text) {
        Objects.requireNonNull(text, "text");
        List<String[]> rules = new ArrayList<>(); // {lhs, alternative text}
        List<Integer> lineNumbers = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int arrow = line.indexOf("->");
            if (arrow < 0) {
                throw new IllegalArgumentException("line " + (i + 1) + ": missing '->'");
            }
            String lhs = line.substring(0, arrow).strip();
            if (lhs.isEmpty() || lhs.matches(".*\\s.*")) {
                throw new IllegalArgumentException("line " + (i + 1) + ": bad left-hand side '" + lhs + "'");
            }
            if (reserved(lhs)) {
                throw new IllegalArgumentException("line " + (i + 1) + ": reserved symbol '" + lhs + "'");
            }
            for (String alt : line.substring(arrow + 2).split("\\|", -1)) {
                rules.add(new String[] {lhs, alt.strip()});
                lineNumbers.add(i + 1);
            }
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("grammar has no rules");
        }
        Set<String> nts = new LinkedHashSet<>();
        for (String[] r : rules) {
            nts.add(r[0]);
        }
        List<Production> prods = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int k = 0; k < rules.size(); k++) {
            String lhs = rules.get(k)[0];
            String alt = rules.get(k)[1];
            int line = lineNumbers.get(k);
            if (alt.isEmpty()) {
                throw new IllegalArgumentException("line " + line + ": empty alternative (write ε)");
            }
            String[] toks = alt.split("\\s+");
            List<String> rhs = new ArrayList<>();
            boolean eps = false;
            for (String t : toks) {
                if (t.equals("ε") || t.equals("%empty")) {
                    eps = true;
                } else if (reserved(t)) {
                    throw new IllegalArgumentException("line " + line + ": reserved symbol '" + t + "'");
                } else {
                    rhs.add(t);
                }
            }
            if (eps && !rhs.isEmpty()) {
                throw new IllegalArgumentException("line " + line + ": ε mixed with other symbols");
            }
            prods.add(new Production(k + 1, lhs, rhs));
            seen.add(lhs);
            seen.addAll(rhs);
        }
        // symbols in first-appearance order: lhs then rhs, rule by rule
        List<String> order = new ArrayList<>(seen);
        return new Grammar(prods, order, nts);
    }

    private static boolean reserved(String s) {
        return s.equals(EOF) || s.equals(ACCEPT) || s.equals("->") || s.equals("|")
            || s.equals("ε") || s.equals("%empty");
    }

    /** Productions; index 0 is the augmented rule. Unmodifiable. */
    public List<Production> productions() {
        return productions;
    }

    /** The start symbol. */
    public String start() {
        return start;
    }

    /** Nonterminals and terminals in first-appearance order. */
    public List<String> symbols() {
        return symbols;
    }

    /** Nonterminals (symbols with at least one rule). */
    public Set<String> nonterminals() {
        return nonterminals;
    }

    /** Terminals (excluding {@code $}). */
    public Set<String> terminals() {
        return terminals;
    }

    /** True if the nonterminal derives the empty string. */
    public boolean nullable(String nonterminal) {
        return nullable.contains(nonterminal);
    }

    /** FIRST set of a symbol (a terminal's FIRST is itself); ε is not included. */
    public Set<String> first(String symbol) {
        if (terminals.contains(symbol)) {
            return Set.of(symbol);
        }
        return Collections.unmodifiableSet(first.get(symbol));
    }

    /** FIRST of {@code beta} followed by the single lookahead {@code la}. */
    Set<String> firstOfSequence(List<String> beta, int from, String la) {
        Set<String> out = new TreeSet<>();
        for (int i = from; i < beta.size(); i++) {
            String s = beta.get(i);
            out.addAll(first(s));
            if (!nonterminals.contains(s) || !nullable.contains(s)) {
                return out;
            }
        }
        out.add(la);
        return out;
    }

    /** FOLLOW set of a nonterminal; FOLLOW(start) contains {@code $}. */
    public Set<String> follow(String nonterminal) {
        return Collections.unmodifiableSet(follow.get(nonterminal));
    }

    /** Nonterminals that derive at least one terminal string. */
    public Set<String> productive() {
        Set<String> prod = new TreeSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : productions.subList(1, productions.size())) {
                boolean ok = true;
                for (String s : p.rhs()) {
                    if (nonterminals.contains(s) && !prod.contains(s)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    changed |= prod.add(p.lhs());
                }
            }
        }
        return prod;
    }

    /** Nonterminals reachable from the start symbol. */
    public Set<String> reachable() {
        Set<String> seen = new TreeSet<>();
        List<String> work = new ArrayList<>();
        seen.add(start);
        work.add(start);
        while (!work.isEmpty()) {
            String n = work.remove(work.size() - 1);
            for (Production p : productions.subList(1, productions.size())) {
                if (!p.lhs().equals(n)) {
                    continue;
                }
                for (String s : p.rhs()) {
                    if (nonterminals.contains(s) && seen.add(s)) {
                        work.add(s);
                    }
                }
            }
        }
        return seen;
    }

    private void computeNullable() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : productions.subList(1, productions.size())) {
                if (!nullable.contains(p.lhs()) && allNullable(p.rhs(), 0)) {
                    nullable.add(p.lhs());
                    changed = true;
                }
            }
        }
    }

    private boolean allNullable(List<String> syms, int from) {
        for (int i = from; i < syms.size(); i++) {
            if (!nullable.contains(syms.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void computeFirst() {
        for (String n : nonterminals) {
            first.put(n, new TreeSet<>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : productions.subList(1, productions.size())) {
                Set<String> target = first.get(p.lhs());
                for (String s : p.rhs()) {
                    changed |= target.addAll(first(s));
                    if (!nullable.contains(s)) {
                        break;
                    }
                }
            }
        }
    }

    private void computeFollow() {
        for (String n : nonterminals) {
            follow.put(n, new TreeSet<>());
        }
        follow.get(start).add(EOF);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : productions.subList(1, productions.size())) {
                List<String> rhs = p.rhs();
                for (int i = 0; i < rhs.size(); i++) {
                    String s = rhs.get(i);
                    if (!nonterminals.contains(s)) {
                        continue;
                    }
                    Set<String> target = follow.get(s);
                    boolean restNullable = true;
                    for (int j = i + 1; j < rhs.size(); j++) {
                        String t = rhs.get(j);
                        changed |= target.addAll(first(t));
                        if (!nullable.contains(t)) {
                            restNullable = false;
                            break;
                        }
                    }
                    if (restNullable) {
                        changed |= target.addAll(follow.get(p.lhs()));
                    }
                }
            }
        }
    }
}
