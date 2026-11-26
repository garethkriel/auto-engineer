package ae.lr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ACTION/GOTO tables built for a {@link Grammar} in one of three {@link Mode}s.
 * States are numbered in breadth-first discovery order from state 0.
 */
public final class LrTable {

    /** LR(1) item; {@code la} is empty for LR(0) items. */
    private record Item(int prod, int dot, String la) { }

    /** LR(0) core of an item. */
    private record Core(int prod, int dot) { }

    private final Grammar grammar;
    private final Mode mode;
    private final List<Map<String, List<Action>>> actions = new ArrayList<>();
    private final List<Map<String, Integer>> gotos = new ArrayList<>();
    private final List<Set<Core>> cores = new ArrayList<>();
    private final List<Conflict> conflicts = new ArrayList<>();

    private LrTable(Grammar grammar, Mode mode) {
        this.grammar = grammar;
        this.mode = mode;
    }

    /** Builds the table. Conflicts are recorded, not thrown. */
    public static LrTable build(Grammar grammar, Mode mode) {
        Objects.requireNonNull(grammar, "grammar");
        Objects.requireNonNull(mode, "mode");
        LrTable t = new LrTable(grammar, mode);
        t.construct();
        return t;
    }

    // ------------------------------------------------------------------ construction

    private void construct() {
        boolean withLa = mode != Mode.SLR;
        List<Set<Item>> states = new ArrayList<>();
        List<Map<String, Integer>> trans = new ArrayList<>();
        discover(withLa, states, trans);
        if (mode == Mode.LALR) {
            merge(states, trans);
        }
        for (int s = 0; s < states.size(); s++) {
            Set<Core> c = new LinkedHashSet<>();
            for (Item it : states.get(s)) {
                c.add(new Core(it.prod(), it.dot()));
            }
            cores.add(c);
            gotos.add(new TreeMap<>());
            actions.add(new TreeMap<>());
        }
        fill(states, trans);
        collectConflicts();
    }

    /** Breadth-first exploration of the (LR(0) or canonical LR(1)) automaton. */
    private void discover(boolean withLa, List<Set<Item>> states, List<Map<String, Integer>> trans) {
        Map<Set<Item>, Integer> index = new HashMap<>();
        Set<Item> start = new LinkedHashSet<>();
        start.add(new Item(0, 0, withLa ? Grammar.EOF : ""));
        Set<Item> s0 = closure(start, withLa);
        states.add(s0);
        index.put(s0, 0);
        for (int i = 0; i < states.size(); i++) {
            Map<String, Integer> out = new LinkedHashMap<>();
            Set<Item> state = states.get(i);
            for (String sym : grammar.symbols()) {
                Set<Item> kernel = new LinkedHashSet<>();
                for (Item it : state) {
                    List<String> rhs = grammar.productions().get(it.prod()).rhs();
                    if (it.dot() < rhs.size() && rhs.get(it.dot()).equals(sym)) {
                        kernel.add(new Item(it.prod(), it.dot() + 1, it.la()));
                    }
                }
                if (kernel.isEmpty()) {
                    continue;
                }
                Set<Item> next = closure(kernel, withLa);
                Integer id = index.get(next);
                if (id == null) {
                    id = states.size();
                    states.add(next);
                    index.put(next, id);
                }
                out.put(sym, id);
            }
            trans.add(out);
        }
    }

    private Set<Item> closure(Set<Item> kernel, boolean withLa) {
        Set<Item> out = new LinkedHashSet<>(kernel);
        List<Item> work = new ArrayList<>(kernel);
        while (!work.isEmpty()) {
            Item it = work.remove(work.size() - 1);
            List<String> rhs = grammar.productions().get(it.prod()).rhs();
            if (it.dot() >= rhs.size() || !grammar.nonterminals().contains(rhs.get(it.dot()))) {
                continue;
            }
            String b = rhs.get(it.dot());
            Set<String> las = withLa
                ? grammar.firstOfSequence(rhs, it.dot() + 1, it.la()) : Set.of("");
            for (Production p : grammar.productions()) {
                if (!p.lhs().equals(b)) {
                    continue;
                }
                for (String la : las) {
                    Item n = new Item(p.index(), 0, la);
                    if (out.add(n)) {
                        work.add(n);
                    }
                }
            }
        }
        return out;
    }

    /** LALR: merge canonical LR(1) states with equal cores; first occurrence fixes the number. */
    private static void merge(List<Set<Item>> states, List<Map<String, Integer>> trans) {
        Map<Set<Core>, Integer> byCore = new HashMap<>();
        int[] newId = new int[states.size()];
        List<Set<Item>> mergedStates = new ArrayList<>();
        List<Map<String, Integer>> mergedTrans = new ArrayList<>();
        for (int s = 0; s < states.size(); s++) {
            Set<Core> key = new LinkedHashSet<>();
            for (Item it : states.get(s)) {
                key.add(new Core(it.prod(), it.dot()));
            }
            Integer id = byCore.get(key);
            if (id == null) {
                id = mergedStates.size();
                byCore.put(key, id);
                mergedStates.add(new LinkedHashSet<>());
                mergedTrans.add(null);
            }
            newId[s] = id;
            mergedStates.get(id).addAll(states.get(s));
        }
        for (int s = 0; s < states.size(); s++) {
            if (mergedTrans.get(newId[s]) != null) {
                continue;
            }
            Map<String, Integer> m = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : trans.get(s).entrySet()) {
                m.put(e.getKey(), newId[e.getValue()]);
            }
            mergedTrans.set(newId[s], m);
        }
        states.clear();
        states.addAll(mergedStates);
        trans.clear();
        trans.addAll(mergedTrans);
    }

    private void fill(List<Set<Item>> states, List<Map<String, Integer>> trans) {
        for (int s = 0; s < states.size(); s++) {
            Map<String, Set<Action>> cell = new TreeMap<>();
            for (Map.Entry<String, Integer> e : trans.get(s).entrySet()) {
                if (grammar.nonterminals().contains(e.getKey())) {
                    gotos.get(s).put(e.getKey(), e.getValue());
                } else {
                    cell.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>())
                        .add(new Action.Shift(e.getValue()));
                }
            }
            for (Item it : states.get(s)) {
                Production p = grammar.productions().get(it.prod());
                if (it.dot() < p.rhs().size()) {
                    continue;
                }
                Set<String> las = mode == Mode.SLR ? followOf(p) : Set.of(it.la());
                for (String la : las) {
                    Action a = p.index() == 0 ? new Action.Accept() : new Action.Reduce(p.index());
                    cell.computeIfAbsent(la, k -> new LinkedHashSet<>()).add(a);
                }
            }
            for (Map.Entry<String, Set<Action>> e : cell.entrySet()) {
                List<Action> list = new ArrayList<>(e.getValue());
                list.sort((x, y) -> Integer.compare(rank(x), rank(y)));
                actions.get(s).put(e.getKey(), Collections.unmodifiableList(list));
            }
        }
    }

    private Set<String> followOf(Production p) {
        return p.index() == 0 ? Set.of(Grammar.EOF) : grammar.follow(p.lhs());
    }

    /** Shifts first, then reduces by ascending production, accept last. */
    private static int rank(Action a) {
        if (a instanceof Action.Shift) {
            return 0;
        }
        if (a instanceof Action.Reduce r) {
            return 1 + r.production();
        }
        return Integer.MAX_VALUE;
    }

    private void collectConflicts() {
        for (int s = 0; s < actions.size(); s++) {
            for (Map.Entry<String, List<Action>> e : actions.get(s).entrySet()) {
                List<Action> list = e.getValue();
                if (list.size() < 2) {
                    continue;
                }
                boolean shift = list.get(0) instanceof Action.Shift;
                conflicts.add(new Conflict(s, e.getKey(),
                    shift ? Conflict.Kind.SHIFT_REDUCE : Conflict.Kind.REDUCE_REDUCE, list));
            }
        }
    }

    // ------------------------------------------------------------------ queries

    /** The grammar this table was built for. */
    public Grammar grammar() {
        return grammar;
    }

    /** The construction mode. */
    public Mode mode() {
        return mode;
    }

    /** Number of states. */
    public int stateCount() {
        return actions.size();
    }

    /** All conflicts ordered by (state, symbol). */
    public List<Conflict> conflicts() {
        return Collections.unmodifiableList(conflicts);
    }

    /** Actions for {@code (state, terminal)}; empty if none, more than one on a conflict. */
    public List<Action> actions(int state, String terminal) {
        return actions.get(state).getOrDefault(terminal, List.of());
    }

    /** Goto target for {@code (state, nonterminal)}. */
    public Optional<Integer> goTo(int state, String nonterminal) {
        return Optional.ofNullable(gotos.get(state).get(nonterminal));
    }

    /** Sorted terminals (possibly {@code $}) that have an action in {@code state}. */
    public Set<String> expected(int state) {
        return new TreeSet<>(actions.get(state).keySet());
    }

    /** Human-readable description of a conflict: the cell and the LR(0) items of its state. */
    public String describe(Conflict c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.kind()).append(" conflict in state ").append(c.state())
            .append(" on '").append(c.symbol()).append("':");
        for (Core core : cores.get(c.state())) {
            Production p = grammar.productions().get(core.prod());
            sb.append("\n  ").append(p.lhs()).append(" ->");
            for (int i = 0; i <= p.rhs().size(); i++) {
                if (i == core.dot()) {
                    sb.append(" .");
                }
                if (i < p.rhs().size()) {
                    sb.append(' ').append(p.rhs().get(i));
                }
            }
        }
        return sb.toString();
    }
}
