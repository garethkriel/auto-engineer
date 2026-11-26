package ae.earley;

import java.util.List;

/** A parse tree: leaves are consumed terminals, nodes are rule applications. */
public sealed interface Tree {

    /** The text covered by this tree. */
    String yield();

    /** Children (empty for a leaf). */
    List<Tree> children();

    /** A consumed terminal. */
    record Leaf(int codePoint) implements Tree {
        @Override
        public String yield() {
            return new String(Character.toChars(codePoint));
        }

        @Override
        public List<Tree> children() {
            return List.of();
        }

        @Override
        public String toString() {
            return new Symbol.Terminal(codePoint).toString();
        }
    }

    /** Application of rule number {@code rule} (index into {@link Grammar#rules()}). */
    record Node(String symbol, int rule, List<Tree> children) implements Tree {
        public Node {
            children = List.copyOf(children);
        }

        @Override
        public String yield() {
            StringBuilder sb = new StringBuilder();
            for (Tree c : children) {
                sb.append(c.yield());
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("(").append(symbol);
            for (Tree c : children) {
                sb.append(' ').append(c);
            }
            return sb.append(')').toString();
        }
    }
}
