package ae.minilang;

import ae.minilang.Ast.*;
import java.util.List;
import java.util.stream.Collectors;

/** Prints a syntax tree as fully parenthesised source that parses back to an equivalent tree. */
final class Printer {
    private Printer() { }

    static String source(Program p) {
        StringBuilder sb = new StringBuilder();
        for (Func f : p.funcs()) {
            sb.append("fn ").append(f.name()).append('(').append(String.join(", ", f.params())).append(") { ")
                .append(stmts(f.body())).append(" }\n");
        }
        for (Stmt s : p.main()) {
            sb.append(stmt(s)).append('\n');
        }
        return sb.toString();
    }

    static String stmts(List<Stmt> body) {
        return body.stream().map(Printer::stmt).collect(Collectors.joining(" "));
    }

    static String stmt(Stmt s) {
        return switch (s) {
            case Let l -> "let " + l.name() + " = " + expr(l.init()) + ";";
            case Assign a -> a.name() + " = " + expr(a.value()) + ";";
            case If i -> "if (" + expr(i.cond()) + ") { " + stmts(i.thenBody()) + " }"
                + (i.elseBody().isEmpty() ? "" : " else { " + stmts(i.elseBody()) + " }");
            case While w -> "while (" + expr(w.cond()) + ") { " + stmts(w.body()) + " }";
            case Return r -> r.value() == null ? "return;" : "return " + expr(r.value()) + ";";
            case Print p -> "print " + expr(p.value()) + ";";
            case ExprStmt e -> expr(e.expr()) + ";";
            case Block b -> "{ " + stmts(b.body()) + " }";
        };
    }

    static String expr(Expr e) {
        return switch (e) {
            case Num n -> n.value() < 0 ? "(" + n.value() + ")" : Long.toString(n.value());
            case Var v -> v.name();
            case Unary u -> "(" + (u.op() == UnOp.NEG ? "-" : "!") + expr(u.operand()) + ")";
            case Binary b -> "(" + expr(b.left()) + " " + b.op().symbol + " " + expr(b.right()) + ")";
            case Call c -> c.name() + "(" + c.args().stream().map(Printer::expr)
                .collect(Collectors.joining(", ")) + ")";
        };
    }
}
