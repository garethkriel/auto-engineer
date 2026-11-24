package ae.parsegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** LR(0) automaton with SLR(1) action/goto tables and a shift-reduce parser. */
public final class Slr {
    /** {@code kind}: 'S' shift to state {@code arg}, 'R' reduce production {@code arg}, 'A' accept. */
    public record Action(char kind, int arg) {}

    /** {@code kind} is "shift/reduce" or "reduce/reduce"; the table keeps the shift, or the lower production. */
    public record Conflict(int state, String terminal, String kind) {}

    private record Item(int prod, int dot) implements Comparable<Item> {
        @Override
        public int compareTo(Item o) {
            return Comparator.comparingInt(Item::prod).thenComparingInt(Item::dot).compare(this, o);
        }
    }

    private final Grammar g;
    private final List<Production> prods = new ArrayList<>(); // grammar productions, then the augmented one
    private final int augmented;
    private final List<Map<String, Action>> action = new ArrayList<>();
    private final List<Map<String, Integer>> transitions = new ArrayList<>();
    private final List<Conflict> conflicts = new ArrayList<>();

    private Slr(Grammar g) {
        this.g = g;
        prods.addAll(g.productions());
        augmented = prods.size();
        prods.add(new Production(augmented, "$accept", List.of(g.start())));
    }

    public static Slr build(Grammar g) {
        Slr s = new Slr(g);
        s.construct(new Analysis(g));
        return s;
    }

    private Set<Item> closure(Set<Item> kernel) {
        TreeSet<Item> out = new TreeSet<>(kernel);
        Deque<Item> work = new ArrayDeque<>(kernel);
        while (!work.isEmpty()) {
            Item it = work.pop();
            List<String> rhs = prods.get(it.prod()).rhs();
            if (it.dot() < rhs.size() && g.isNonterminal(rhs.get(it.dot()))) {
                for (Production q : g.productionsOf(rhs.get(it.dot()))) {
                    Item n = new Item(q.id(), 0);
                    if (out.add(n)) {
                        work.push(n);
                    }
                }
            }
        }
        return out;
    }

    private void construct(Analysis an) {
        List<Set<Item>> states = new ArrayList<>();
        Map<Set<Item>, Integer> index = new HashMap<>();
        Set<Item> k0 = new TreeSet<>(List.of(new Item(augmented, 0)));
        states.add(closure(k0));
        index.put(k0, 0);
        for (int i = 0; i < states.size(); i++) {
            Map<String, Set<Item>> next = new LinkedHashMap<>();
            for (Item it : states.get(i)) {
                List<String> rhs = prods.get(it.prod()).rhs();
                if (it.dot() < rhs.size()) {
                    next.computeIfAbsent(rhs.get(it.dot()), k -> new TreeSet<>()).add(new Item(it.prod(), it.dot() + 1));
                }
            }
            Map<String, Integer> tr = new TreeMap<>();
            for (Map.Entry<String, Set<Item>> e : next.entrySet()) {
                Integer target = index.get(e.getValue());
                if (target == null) {
                    target = states.size();
                    index.put(e.getValue(), target);
                    states.add(closure(e.getValue()));
                }
                tr.put(e.getKey(), target);
            }
            transitions.add(tr);
        }
        Set<Conflict> found = new LinkedHashSet<>();
        for (int i = 0; i < states.size(); i++) {
            Map<String, Action> row = new TreeMap<>();
            for (Map.Entry<String, Integer> e : transitions.get(i).entrySet()) {
                if (!g.isNonterminal(e.getKey())) {
                    row.put(e.getKey(), new Action('S', e.getValue()));
                }
            }
            for (Item it : states.get(i)) {
                Production p = prods.get(it.prod());
                if (it.dot() < p.rhs().size()) {
                    continue;
                }
                if (it.prod() == augmented) {
                    row.put(Grammar.END, new Action('A', 0));
                    continue;
                }
                Action red = new Action('R', p.id());
                for (String t : an.follow(p.lhs())) {
                    Action old = row.get(t);
                    if (old == null) {
                        row.put(t, red);
                    } else if (!old.equals(red)) {
                        found.add(new Conflict(i, t, old.kind() == 'S' ? "shift/reduce" : "reduce/reduce"));
                    }
                }
            }
            action.add(row);
        }
        conflicts.addAll(found);
        conflicts.sort(Comparator.comparingInt(Conflict::state).thenComparing(Conflict::terminal).thenComparing(Conflict::kind));
    }

    public int stateCount() {
        return action.size();
    }

    public boolean isSlr1() {
        return conflicts.isEmpty();
    }

    public List<Conflict> conflicts() {
        return List.copyOf(conflicts);
    }

    /** The action for a state and terminal ({@code $} for end of input), or null for an error entry. */
    public Action action(int state, String terminal) {
        return action.get(state).get(terminal);
    }

    /** The state reached from {@code state} on a symbol, or -1 if there is no transition. */
    public int go(int state, String symbol) {
        return transitions.get(state).getOrDefault(symbol, -1);
    }

    public Tree parse(List<String> tokens) {
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("grammar is not SLR(1): " + conflicts.size() + " conflict(s)");
        }
        if (tokens.contains(Grammar.END)) {
            throw new IllegalArgumentException("token list must not contain " + Grammar.END);
        }
        List<Integer> states = new ArrayList<>();
        List<Tree> trees = new ArrayList<>();
        states.add(0);
        int pos = 0;
        while (true) {
            int state = states.get(states.size() - 1);
            String look = pos < tokens.size() ? tokens.get(pos) : Grammar.END;
            Action act = action.get(state).get(look);
            if (act == null) {
                throw new ParseException(pos, action.get(state).keySet());
            }
            switch (act.kind()) {
                case 'S' -> {
                    states.add(act.arg());
                    trees.add(Tree.leaf(look));
                    pos++;
                }
                case 'R' -> {
                    Production p = prods.get(act.arg());
                    int n = p.rhs().size();
                    List<Tree> kids = new ArrayList<>(trees.subList(trees.size() - n, trees.size()));
                    trees.subList(trees.size() - n, trees.size()).clear();
                    states.subList(states.size() - n, states.size()).clear();
                    trees.add(new Tree(p.lhs(), p.id(), kids));
                    states.add(transitions.get(states.get(states.size() - 1)).get(p.lhs()));
                }
                default -> {
                    return trees.get(0);
                }
            }
        }
    }
}
