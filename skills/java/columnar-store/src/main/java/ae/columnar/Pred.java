package ae.columnar;

import java.util.Objects;

/** A single comparison {@code column op value}; predicate lists are AND-ed. */
public record Pred(String column, Op op, long value) {
    public Pred {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(op, "op");
    }
}
