package ae;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/** Immutable running statistics over amounts. min/max are null only when count is 0. */
public record Stats(long count, BigDecimal sum, BigDecimal min, BigDecimal max) {
    public static final Stats EMPTY = new Stats(0, BigDecimal.ZERO, null, null);

    /** Returns a new Stats including {@code v}. */
    public Stats accept(BigDecimal v) {
        Objects.requireNonNull(v, "v");
        if (count == 0) {
            return new Stats(1, v, v, v);
        }
        return new Stats(count + 1, sum.add(v), min.min(v), max.max(v));
    }

    /** Merges two Stats; associative, with EMPTY as identity. */
    public Stats combine(Stats o) {
        if (o.count == 0) {
            return this;
        }
        if (count == 0) {
            return o;
        }
        return new Stats(count + o.count, sum.add(o.sum), min.min(o.min), max.max(o.max));
    }

    /** Mean at scale 2, HALF_UP; empty when there is no data. */
    public Optional<BigDecimal> mean() {
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
    }
}
