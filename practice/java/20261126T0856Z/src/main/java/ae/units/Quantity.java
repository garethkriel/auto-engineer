package ae.units;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An exact value together with a {@link Unit}. */
public record Quantity(Rational value, Unit unit) {

    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    public Quantity {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unit, "unit");
    }

    /** Parses {@code "<number> <unit expression>"}; the unit part may be omitted (dimensionless). */
    public static Quantity parse(String text) {
        Objects.requireNonNull(text, "text");
        String s = text.strip();
        Matcher m = NUMBER.matcher(s);
        if (!m.lookingAt()) {
            throw new IllegalArgumentException("expected a number at the start of '" + text + "'");
        }
        Rational value = Rational.parseDecimal(m.group());
        String rest = s.substring(m.end()).strip();
        return new Quantity(value, UnitRegistry.parse(rest.isEmpty() ? "1" : rest));
    }

    /** SI value: {@code value * factor + offset}. */
    public Rational siValue() {
        return value.times(unit.factor()).plus(unit.offset());
    }

    public Quantity to(Unit target) {
        requireSameDimension(target.dimension());
        return new Quantity(siValue().minus(target.offset()).dividedBy(target.factor()), target);
    }

    public Quantity to(String unitText) {
        return to(UnitRegistry.parse(unitText));
    }

    /** Sum in the unit of {@code this}; both units must share a dimension and be non-affine. */
    public Quantity plus(Quantity o) {
        requireAdditive(o);
        return new Quantity(value.plus(o.to(unit).value), unit);
    }

    public Quantity minus(Quantity o) {
        requireAdditive(o);
        return new Quantity(value.minus(o.to(unit).value), unit);
    }

    public Quantity times(Quantity o) {
        Unit product = unit.times(o.unit);
        return new Quantity(value.times(o.value), product);
    }

    public Quantity dividedBy(Quantity o) {
        Unit quotient = unit.dividedBy(o.unit);
        return new Quantity(value.dividedBy(o.value), quotient);
    }

    public Quantity pow(int n) {
        Unit powered = unit.pow(n);
        return new Quantity(value.pow(n), powered);
    }

    /** True when both quantities have the same dimension and exactly the same SI value. */
    public boolean equalsQuantity(Quantity o) {
        return unit.dimension().equals(o.unit.dimension()) && siValue().equals(o.siValue());
    }

    private void requireSameDimension(Dimension other) {
        if (!unit.dimension().equals(other)) {
            throw new IllegalArgumentException(
                "dimension mismatch: " + unit.dimension().describe() + " vs " + other.describe());
        }
    }

    private void requireAdditive(Quantity o) {
        requireSameDimension(o.unit.dimension());
        if (unit.isAffine() || o.unit.isAffine()) {
            throw new IllegalArgumentException("cannot add or subtract affine-unit quantities; convert to K first");
        }
    }

    @Override
    public String toString() {
        return value + " [" + unit.dimension().describe() + " x" + unit.factor() + "]";
    }
}
