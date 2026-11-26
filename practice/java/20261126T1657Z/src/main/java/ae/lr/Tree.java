package ae.lr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Parse tree node. A leaf is a terminal ({@code production == -1}); an inner node is a
 * nonterminal with the index of the production that built it (possibly with no children).
 * All traversals are iterative so that very deep trees do not overflow the stack.
 */
public record Tree(String symbol, List<Tree> children, int production) {

    /** Compact constructor: defensive copy. */
    public Tree {
        children = List.copyOf(children);
    }

    /** Creates a terminal leaf. */
    public static Tree leaf(String token) {
        return new Tree(token, List.of(), -1);
    }

    /** True for a terminal. */
    public boolean isLeaf() {
        return production < 0;
    }

    /** The terminals below this node, left to right. */
    public List<String> yield() {
        List<String> out = new ArrayList<>();
        Deque<Tree> stack = new ArrayDeque<>();
        stack.push(this);
        while (!stack.isEmpty()) {
            Tree t = stack.pop();
            if (t.isLeaf()) {
                out.add(t.symbol);
            } else {
                for (int i = t.children.size() - 1; i >= 0; i--) {
                    stack.push(t.children.get(i));
                }
            }
        }
        return out;
    }

    /** S-expression form: leaf = symbol, node = {@code (sym child...)}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(this);
        while (!stack.isEmpty()) {
            Object o = stack.pop();
            if (o instanceof String s) {
                sb.append(s);
            } else {
                Tree t = (Tree) o;
                if (t.isLeaf()) {
                    sb.append(t.symbol);
                } else {
                    sb.append('(').append(t.symbol);
                    stack.push(")");
                    for (int i = t.children.size() - 1; i >= 0; i--) {
                        stack.push(t.children.get(i));
                        stack.push(" ");
                    }
                }
            }
        }
        return sb.toString();
    }
}
