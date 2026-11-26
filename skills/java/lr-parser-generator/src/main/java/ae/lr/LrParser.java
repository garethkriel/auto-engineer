package ae.lr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Table-driven shift/reduce parser over a conflict-free {@link LrTable}. */
public final class LrParser {

    private final LrTable table;
    private final Grammar grammar;

    /**
     * Creates a parser.
     *
     * @throws IllegalArgumentException if the table has conflicts
     */
    public LrParser(LrTable table) {
        Objects.requireNonNull(table, "table");
        if (!table.conflicts().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("table has ").append(table.conflicts().size()).append(" conflict(s)");
            sb.append("; first: ").append(table.describe(table.conflicts().get(0)));
            throw new IllegalArgumentException(sb.toString());
        }
        this.table = table;
        this.grammar = table.grammar();
    }

    /**
     * Parses a token sequence.
     *
     * @throws LrParseException on a syntax error
     * @throws IllegalArgumentException if a token equals {@code $}
     */
    public Tree parse(List<String> tokens) {
        Objects.requireNonNull(tokens, "tokens");
        for (String t : tokens) {
            if (Grammar.EOF.equals(t)) {
                throw new IllegalArgumentException("token '$' is reserved for end of input");
            }
        }
        List<Integer> states = new ArrayList<>();
        List<Tree> trees = new ArrayList<>();
        states.add(0);
        int pos = 0;
        while (true) {
            int state = states.get(states.size() - 1);
            String la = pos < tokens.size() ? tokens.get(pos) : Grammar.EOF;
            List<Action> acts = table.actions(state, la);
            if (acts.isEmpty()) {
                throw new LrParseException(pos, la, table.expected(state));
            }
            Action a = acts.get(0);
            if (a instanceof Action.Shift s) {
                states.add(s.state());
                trees.add(Tree.leaf(la));
                pos++;
            } else if (a instanceof Action.Reduce r) {
                Production p = grammar.productions().get(r.production());
                int k = p.rhs().size();
                List<Tree> kids = new ArrayList<>(trees.subList(trees.size() - k, trees.size()));
                trees.subList(trees.size() - k, trees.size()).clear();
                states.subList(states.size() - k, states.size()).clear();
                int top = states.get(states.size() - 1);
                states.add(table.goTo(top, p.lhs()).orElseThrow());
                trees.add(new Tree(p.lhs(), kids, p.index()));
            } else {
                return trees.get(trees.size() - 1);
            }
        }
    }
}
