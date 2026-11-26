package ae.units;

import java.util.Arrays;

/** Immutable vector of integer exponents over the seven SI base dimensions L M T I Th N J. */
public final class Dimension {

    private static final String[] SYMBOLS = {"L", "M", "T", "I", "Th", "N", "J"};

    public static final Dimension NONE = new Dimension(new int[7]);
    public static final Dimension LENGTH = unit(0);
    public static final Dimension MASS = unit(1);
    public static final Dimension TIME = unit(2);
    public static final Dimension CURRENT = unit(3);
    public static final Dimension TEMPERATURE = unit(4);
    public static final Dimension AMOUNT = unit(5);
    public static final Dimension LUMINOUS = unit(6);

    private final int[] exps;

    private Dimension(int[] exps) {
        this.exps = exps;
    }

    private static Dimension unit(int index) {
        int[] e = new int[7];
        e[index] = 1;
        return new Dimension(e);
    }

    /** Builds a dimension from exactly seven exponents in the order L M T I Th N J. */
    public static Dimension of(int... exponents) {
        if (exponents.length != SYMBOLS.length) {
            throw new IllegalArgumentException("expected 7 exponents but got " + exponents.length);
        }
        return new Dimension(exponents.clone());
    }

    public int exponent(int index) {
        return exps[java.util.Objects.checkIndex(index, exps.length)];
    }

    public Dimension times(Dimension o) {
        int[] e = new int[7];
        for (int i = 0; i < e.length; i++) {
            e[i] = Math.addExact(exps[i], o.exps[i]);
        }
        return new Dimension(e);
    }

    public Dimension dividedBy(Dimension o) {
        int[] e = new int[7];
        for (int i = 0; i < e.length; i++) {
            e[i] = Math.subtractExact(exps[i], o.exps[i]);
        }
        return new Dimension(e);
    }

    public Dimension pow(int n) {
        int[] e = new int[7];
        for (int i = 0; i < e.length; i++) {
            e[i] = Math.multiplyExact(exps[i], n);
        }
        return new Dimension(e);
    }

    public boolean isDimensionless() {
        for (int x : exps) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    /** Non-zero exponents in the order L M T I Th N J, e.g. {@code L M T^-2}; {@code 1} when dimensionless. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < exps.length; i++) {
            if (exps[i] == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(SYMBOLS[i]);
            if (exps[i] != 1) {
                sb.append('^').append(exps[i]);
            }
        }
        return sb.length() == 0 ? "1" : sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Dimension d && Arrays.equals(exps, d.exps);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(exps);
    }

    @Override
    public String toString() {
        return describe();
    }
}
