package tmpl;

import java.util.List;

/** Expression AST. {@code offset} fields are absolute source offsets used for error reporting. */
sealed interface Expr {
    /** A literal: String, BigDecimal, Boolean or null. */
    record Lit(Object value) implements Expr {
    }

    /** A dotted variable path such as {@code user.tags.0}. */
    record Path(List<String> parts, int offset) implements Expr {
    }

    record Not(Expr operand) implements Expr {
    }

    record And(Expr left, Expr right) implements Expr {
    }

    record Or(Expr left, Expr right) implements Expr {
    }

    /** A comparison; {@code offset} is the position of the operator. */
    record Cmp(String op, Expr left, Expr right, int offset) implements Expr {
    }
}
