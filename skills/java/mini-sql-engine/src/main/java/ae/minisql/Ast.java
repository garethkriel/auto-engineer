package ae.minisql;

import java.util.List;

/** Syntax tree. Identifiers are stored lower-case. Records give structural equality (used for GROUP BY matching). */
final class Ast {
    private Ast() {}

    sealed interface Expr permits Lit, Col, Un, Bin, IsNull, In, Like, Agg {}

    record Lit(Object value) implements Expr {}

    /** {@code table} is null for an unqualified reference. */
    record Col(String table, String name) implements Expr {
        @Override public String toString() { return table == null ? name : table + "." + name; }
    }

    /** op is "-" or "NOT". */
    record Un(String op, Expr e) implements Expr {}

    /** op is one of + - * / % || = <> < <= > >= AND OR. */
    record Bin(String op, Expr l, Expr r) implements Expr {}

    record IsNull(Expr e, boolean negated) implements Expr {}

    record In(Expr e, List<Expr> items, boolean negated) implements Expr {}

    record Like(Expr e, Expr pattern, boolean negated) implements Expr {}

    /** fn is count|sum|min|max; arg null means COUNT(*). */
    record Agg(String fn, Expr arg, boolean distinct) implements Expr {}

    sealed interface Stmt permits CreateTable, DropTable, Insert, Update, Delete, Select, Begin, Commit, Rollback {}

    enum Type { INT, TEXT, BOOL }

    record ColDef(String name, Type type, boolean notNull, boolean primaryKey) {}

    record CreateTable(String name, List<ColDef> cols) implements Stmt {}

    record DropTable(String name) implements Stmt {}

    /** cols null = all columns in declaration order. */
    record Insert(String table, List<String> cols, List<List<Expr>> rows) implements Stmt {}

    record Assign(String column, Expr value) {}

    record Update(String table, List<Assign> sets, Expr where) implements Stmt {}

    record Delete(String table, Expr where) implements Stmt {}

    record Begin() implements Stmt {}

    record Commit() implements Stmt {}

    record Rollback() implements Stmt {}

    /** {@code star}: SELECT * (starTable null) or t.* ; otherwise expr/alias. */
    record Item(Expr expr, String alias, boolean star, String starTable) {}

    record From(String table, String alias) {}

    record Join(boolean left, From from, Expr on) {}

    record OrderKey(Expr expr, boolean desc) {}

    record Select(boolean distinct, List<Item> items, From from, List<Join> joins, Expr where, List<Expr> groupBy,
            Expr having, List<OrderKey> order, Long limit, Long offset) implements Stmt {}
}
