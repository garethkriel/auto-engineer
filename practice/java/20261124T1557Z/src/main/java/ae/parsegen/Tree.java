package ae.parsegen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A parse tree. Terminal leaves have {@code production == -1}; nonterminal nodes carry the id of the production
 * that built them (an epsilon node has no children).
 */
public record Tree(String symbol, int production, List<Tree> children) {
    public Tree {
        children = List.copyOf(children);
    }

    public static Tree leaf(String terminal) {
        return new Tree(terminal, -1, List.of());
    }

    public boolean isLeaf() {
        return production < 0;
    }

    /** Terminal leaves left to right. */
    public List<String> yield() {
        List<String> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(Tree t, List<String> out) {
        if (t.isLeaf()) {
            out.add(t.symbol);
            return;
        }
        for (Tree c : t.children) {
            collect(c, out);
        }
    }

    /** S-expression: a leaf prints its symbol, a node prints {@code (Symbol child...)}. */
    public String sexp() {
        StringBuilder sb = new StringBuilder();
        sexp(sb);
        return sb.toString();
    }

    private void sexp(StringBuilder sb) {
        if (isLeaf()) {
            sb.append(symbol);
            return;
        }
        sb.append('(').append(symbol);
        for (Tree c : children) {
            sb.append(' ');
            c.sexp(sb);
        }
        sb.append(')');
    }

    /** Checks that every node's children are exactly the right-hand side of the production it names. */
    public void validate(Grammar g) {
        if (isLeaf()) {
            if (g.isNonterminal(symbol) || !children.isEmpty()) {
                throw new IllegalStateException("bad leaf " + symbol);
            }
            return;
        }
        Production p = g.production(production);
        if (!p.lhs().equals(symbol)) {
            throw new IllegalStateException("node " + symbol + " built by " + p);
        }
        List<String> kids = children.stream().map(Tree::symbol).collect(Collectors.toList());
        if (!kids.equals(p.rhs())) {
            throw new IllegalStateException("children " + kids + " do not match " + p);
        }
        for (Tree c : children) {
            c.validate(g);
        }
    }
}
