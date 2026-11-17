package tpl;

import java.util.List;

/** The template syntax tree. */
sealed interface Node {

    record Text(String text) implements Node { }

    /** {@code {{ operand | filters }}}; {@code offset} is where the operand starts. */
    record Output(Operand operand, List<Filter> filters, int offset) implements Node { }

    record If(Cond cond, List<Node> then, List<Node> otherwise) implements Node { }

    record For(String variable, Operand.Path source, List<Node> body) implements Node { }

    /** A filter call with an optional literal argument. */
    record Filter(String name, Object argument) { }

    sealed interface Operand {
        record Path(String[] segments, int offset) implements Operand {
            String text() {
                return String.join(".", segments);
            }
        }

        record Str(String value) implements Operand { }

        record Int(long value) implements Operand { }
    }

    sealed interface Cond {
        record Truthy(Operand operand) implements Cond { }

        record Not(Cond inner) implements Cond { }

        record And(Cond left, Cond right) implements Cond { }

        record Or(Cond left, Cond right) implements Cond { }

        record Cmp(Operand left, boolean equal, Operand right) implements Cond { }
    }
}
