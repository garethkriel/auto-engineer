package ae.peg;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Parse-tree node: one per successful rule invocation that ends up in the result. */
public record Node(String rule, int start, int end, List<Node> children) {
    public Node {
        children = List.copyOf(children);
    }

    public String text(String input) {
        return input.substring(start, end);
    }

    /** All descendants (and this node) invoked for the given rule, in document order. */
    public List<Node> find(String name) {
        List<Node> out = new ArrayList<>();
        collect(name, out);
        return out;
    }

    private void collect(String name, List<Node> out) {
        if (rule.equals(name)) {
            out.add(this);
        }
        children.forEach(c -> c.collect(name, out));
    }

    /** S-expression: leaf = {@code (Rule "text")}, inner = {@code (Rule child child)}. */
    public String sexp(String input) {
        if (children.isEmpty()) {
            return "(" + rule + " \"" + text(input) + "\")";
        }
        return "(" + rule + " " + children.stream().map(c -> c.sexp(input)).collect(Collectors.joining(" ")) + ")";
    }
}
