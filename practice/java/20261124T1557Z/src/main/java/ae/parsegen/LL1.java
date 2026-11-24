package ae.parsegen;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** LL(1) predictive table and table-driven parser. */
public final class LL1 {
    /** Two or more productions compete for one table cell. */
    public record Conflict(String nonterminal, String terminal, List<Integer> productions) {}

    private final Grammar g;
    private final Map<String, Map<String, TreeSet<Integer>>> table = new TreeMap<>();
    private final List<Conflict> conflicts = new ArrayList<>();

    private LL1(Grammar g) {
        this.g = g;
    }

    public static LL1 build(Grammar g) {
        LL1 t = new LL1(g);
        Analysis a = new Analysis(g);
        for (Production p : g.productions()) {
            Map<String, TreeSet<Integer>> row = t.table.computeIfAbsent(p.lhs(), k -> new TreeMap<>());
            for (String term : a.firstSeq(p.rhs())) {
                row.computeIfAbsent(term, k -> new TreeSet<>()).add(p.id());
            }
            if (a.nullableSeq(p.rhs())) {
                for (String term : a.follow(p.lhs())) {
                    row.computeIfAbsent(term, k -> new TreeSet<>()).add(p.id());
                }
            }
        }
        for (Map.Entry<String, Map<String, TreeSet<Integer>>> row : t.table.entrySet()) {
            for (Map.Entry<String, TreeSet<Integer>> cell : row.getValue().entrySet()) {
                if (cell.getValue().size() > 1) {
                    t.conflicts.add(new Conflict(row.getKey(), cell.getKey(), List.copyOf(cell.getValue())));
                }
            }
        }
        return t;
    }

    public boolean isLL1() {
        return conflicts.isEmpty();
    }

    public List<Conflict> conflicts() {
        return List.copyOf(conflicts);
    }

    /** Number of non-empty table cells. */
    public int size() {
        int n = 0;
        for (Map<String, TreeSet<Integer>> row : table.values()) {
            n += row.size();
        }
        return n;
    }

    /** The lowest-numbered production in the cell, if any. */
    public OptionalInt entry(String nonterminal, String terminal) {
        Map<String, TreeSet<Integer>> row = table.get(nonterminal);
        if (row == null || !row.containsKey(terminal)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(row.get(terminal).first());
    }

    private static final class Node {
        final String symbol;
        int production = -1;
        final List<Node> kids = new ArrayList<>();

        Node(String symbol) {
            this.symbol = symbol;
        }
    }

    public Tree parse(List<String> tokens) {
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("grammar is not LL(1): " + conflicts.size() + " conflict(s)");
        }
        if (tokens.contains(Grammar.END)) {
            throw new IllegalArgumentException("token list must not contain " + Grammar.END);
        }
        Node root = new Node(g.start());
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        int pos = 0;
        while (!stack.isEmpty()) {
            Node top = stack.pop();
            String look = pos < tokens.size() ? tokens.get(pos) : Grammar.END;
            if (!g.isNonterminal(top.symbol)) {
                if (!top.symbol.equals(look)) {
                    throw new ParseException(pos, Set.of(top.symbol));
                }
                pos++;
                continue;
            }
            Map<String, TreeSet<Integer>> row = table.get(top.symbol);
            TreeSet<Integer> cell = row.get(look);
            if (cell == null) {
                throw new ParseException(pos, row.keySet());
            }
            Production p = g.production(cell.first());
            top.production = p.id();
            for (String s : p.rhs()) {
                top.kids.add(new Node(s));
            }
            for (int i = top.kids.size() - 1; i >= 0; i--) {
                stack.push(top.kids.get(i));
            }
        }
        if (pos < tokens.size()) {
            // the stack emptied before the input did: the start symbol cannot be followed by a token
            throw new ParseException(pos, Set.of(Grammar.END));
        }
        return convert(root);
    }

    private static Tree convert(Node n) {
        List<Tree> kids = new ArrayList<>(n.kids.size());
        for (Node k : n.kids) {
            kids.add(convert(k));
        }
        return n.production < 0 ? Tree.leaf(n.symbol) : new Tree(n.symbol, n.production, kids);
    }
}
