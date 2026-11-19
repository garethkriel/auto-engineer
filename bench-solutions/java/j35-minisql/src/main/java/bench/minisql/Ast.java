package bench.minisql;

import java.util.List;

/** Statement AST. */
final class Ast {
    private Ast() { }

    sealed interface Stmt { }

    record ColDef(String name, boolean isInt) { }

    record Create(String table, List<ColDef> cols) implements Stmt { }

    record Drop(String table) implements Stmt { }

    record Insert(String table, List<String> cols, List<List<Expr>> rows) implements Stmt { }

    record Update(String table, List<String> cols, List<Expr> values, Expr where) implements Stmt { }

    record Delete(String table, Expr where) implements Stmt { }

    /** alias may be null. */
    record Item(Expr expr, String alias) { }

    /** pos is set for a bare integer item, name for a bare identifier item. */
    record OrderItem(Expr expr, Long pos, String name, boolean desc) { }

    /** items == null means '*'. limit/offset are -1 when absent. */
    record Select(List<Item> items, String table, Expr where, List<String> groupBy, Expr having,
                  List<OrderItem> order, long limit, long offset) implements Stmt { }
}
