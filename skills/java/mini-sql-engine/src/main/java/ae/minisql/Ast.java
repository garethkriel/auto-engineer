package ae.minisql;

import java.util.List;
import java.util.function.Consumer;

/** Syntax tree: sealed expression and statement hierarchies. Names are already lowercased. */
final class Ast {
    private Ast() {}

    sealed interface Expr permits Lit, Col, Unary, Binary, IsNull, Like, In, Between, Call {}

    record Lit(Object value) implements Expr {}

    record Col(String qualifier, String name, int pos) implements Expr {}

    /** op is "-" or "NOT". */
    record Unary(String op, Expr operand, int pos) implements Expr {}

    /** op is one of AND OR = != <> < <= > >= + - * / % ||. */
    record Binary(String op, Expr left, Expr right, int pos) implements Expr {}

    record IsNull(Expr operand, boolean negated) implements Expr {}

    record Like(Expr operand, Expr pattern, boolean negated) implements Expr {}

    record In(Expr operand, List<Expr> items, boolean negated) implements Expr {}

    record Between(Expr operand, Expr low, Expr high, boolean negated) implements Expr {}

    /** name is uppercase; star is COUNT(*). */
    record Call(String name, List<Expr> args, boolean star, int pos) implements Expr {}

    sealed interface Stmt permits CreateTable, DropTable, Insert, Update, Delete, Select, Begin, Commit, Rollback {}

    record ColDef(String name, String type, boolean notNull, boolean primaryKey) {}

    record CreateTable(String name, List<ColDef> columns) implements Stmt {}

    record DropTable(String name) implements Stmt {}

    /** columns is null when the statement lists none. */
    record Insert(String table, List<String> columns, List<List<Expr>> rows) implements Stmt {}

    record Assign(String column, Expr value, int pos) {}

    record Update(String table, List<Assign> assignments, Expr where) implements Stmt {}

    record Delete(String table, Expr where) implements Stmt {}

    /** A select-list entry: an expression, or a star when expr is null (qualifier may name one table). */
    record Item(Expr expr, String alias, String text, boolean star, String starQualifier, int pos) {}

    record TableRef(String name, String alias, int pos) {}

    record Join(boolean left, TableRef table, Expr on) {}

    record OrderKey(Expr expr, boolean desc) {}

    record Select(boolean distinct, List<Item> items, TableRef from, List<Join> joins, Expr where,
                  List<Col> groupBy, Expr having, List<OrderKey> orderBy, Integer limit, Integer offset) implements Stmt {}

    record Begin() implements Stmt {}

    record Commit() implements Stmt {}

    record Rollback() implements Stmt {}

    static boolean isAggregateName(String upper) {
        return upper.equals("COUNT") || upper.equals("SUM") || upper.equals("MIN") || upper.equals("MAX")
                || upper.equals("AVG");
    }

    /** Pre-order visit of e and every sub-expression. */
    static void walk(Expr e, Consumer<Expr> visit) {
        visit.accept(e);
        switch (e) {
            case Lit l -> { }
            case Col c -> { }
            case Unary u -> walk(u.operand(), visit);
            case Binary b -> {
                walk(b.left(), visit);
                walk(b.right(), visit);
            }
            case IsNull n -> walk(n.operand(), visit);
            case Like k -> {
                walk(k.operand(), visit);
                walk(k.pattern(), visit);
            }
            case In i -> {
                walk(i.operand(), visit);
                i.items().forEach(x -> walk(x, visit));
            }
            case Between b -> {
                walk(b.operand(), visit);
                walk(b.low(), visit);
                walk(b.high(), visit);
            }
            case Call c -> c.args().forEach(x -> walk(x, visit));
        }
    }

    static boolean containsAggregate(Expr e) {
        boolean[] found = {false};
        walk(e, x -> {
            if (x instanceof Call c && isAggregateName(c.name())) {
                found[0] = true;
            }
        });
        return found[0];
    }
}
