package ae.datalog;

/** Fixpoint strategy. */
public enum Mode {
    /** Re-join every rule against the full relations each round (the oracle). */
    NAIVE,
    /** Join each rule once per same-stratum body atom, using only last round's new tuples there. */
    SEMI_NAIVE
}
