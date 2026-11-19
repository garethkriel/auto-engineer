package ae.locks;

/**
 * Multi-granularity lock modes: intention-shared, intention-exclusive, shared,
 * shared-with-intention-exclusive and exclusive.
 *
 * <p>The modes form the lattice {@code IS < {IX, S} < SIX < X}; {@link #join} is its least upper bound.
 */
public enum LockMode {
    IS, IX, S, SIX, X;

    /** Rows and columns in declaration order. */
    private static final boolean[][] COMPATIBLE = {
        /* IS  */ {true, true, true, true, false},
        /* IX  */ {true, true, false, false, false},
        /* S   */ {true, false, true, false, false},
        /* SIX */ {true, false, false, false, false},
        /* X   */ {false, false, false, false, false},
    };

    /** True if two different transactions may hold this mode and {@code other} on the same resource. */
    public boolean compatibleWith(LockMode other) {
        return COMPATIBLE[ordinal()][other.ordinal()];
    }

    /** Least upper bound of two modes (the mode needed to satisfy both). */
    public LockMode join(LockMode other) {
        if (this == other) {
            return this;
        }
        if (this == X || other == X) {
            return X;
        }
        if (this == SIX || other == SIX) {
            return SIX;
        }
        if (this == IS) {
            return other;
        }
        if (other == IS) {
            return this;
        }
        return SIX; // IX joined with S
    }

    /** True if holding this mode already satisfies a request for {@code other}. */
    public boolean covers(LockMode other) {
        return join(other) == this;
    }

    /** The mode that must be held on every ancestor of a resource locked in this mode. */
    public LockMode intention() {
        return (this == IS || this == S) ? IS : IX;
    }
}
