package tmpl;

import java.util.List;

/** Template AST. */
sealed interface Node {
    record Text(String text) implements Node {
    }

    record FilterCall(String name, List<Object> args, int offset) {
    }

    record Output(Expr expr, List<FilterCall> filters) implements Node {
    }

    record Branch(Expr condition, List<Node> body) {
    }

    record If(List<Branch> branches, List<Node> orElse) implements Node {
    }

    /** {@code sourceOffset} is where the iterated expression starts. */
    record For(String variable, Expr source, int sourceOffset, List<Node> body) implements Node {
    }
}
