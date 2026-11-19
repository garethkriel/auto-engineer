package bench.minisql;

/** Expression AST. */
sealed interface Expr {
    record Lit(Object value) implements Expr { }

    /** A column reference; idx is filled in by name resolution. */
    final class Col implements Expr {
        final String name;
        int idx = -1;

        Col(String name) { this.name = name; }
    }

    record Neg(Expr e) implements Expr { }

    record Not(Expr e) implements Expr { }

    record Bin(String op, Expr l, Expr r) implements Expr { }

    record IsNull(Expr e, boolean negated) implements Expr { }

    /** Aggregate call; arg is null for COUNT(*). */
    record Agg(String fn, Expr arg) implements Expr { }
}
