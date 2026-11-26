package ae.units;

import java.util.Objects;

/**
 * A unit: SI value = {@code v * factor + offset}. Units with a non-zero offset (degC, degF) are
 * <em>affine</em>: they may only stand alone and cannot take part in products, quotients or powers.
 */
public record Unit(Dimension dimension, Rational factor, Rational offset) {

    public Unit {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(factor, "factor");
        Objects.requireNonNull(offset, "offset");
        if (factor.signum() <= 0) {
            throw new IllegalArgumentException("factor must be positive: " + factor);
        }
    }

    public boolean isAffine() {
        return offset.signum() != 0;
    }

    public Unit times(Unit o) {
        requireLinear(this);
        requireLinear(o);
        return new Unit(dimension.times(o.dimension), factor.times(o.factor), Rational.of(0));
    }

    public Unit dividedBy(Unit o) {
        requireLinear(this);
        requireLinear(o);
        return new Unit(dimension.dividedBy(o.dimension), factor.dividedBy(o.factor), Rational.of(0));
    }

    /** {@code pow(1)} returns this unit even when affine; every other exponent rejects affine units. */
    public Unit pow(int n) {
        if (n == 1) {
            return this;
        }
        requireLinear(this);
        return new Unit(dimension.pow(n), factor.pow(n), Rational.of(0));
    }

    private static void requireLinear(Unit u) {
        if (u.isAffine()) {
            throw new IllegalArgumentException("affine unit (offset " + u.offset + ") cannot be combined or raised to a power");
        }
    }
}
