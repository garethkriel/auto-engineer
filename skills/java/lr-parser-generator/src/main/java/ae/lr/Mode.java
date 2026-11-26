package ae.lr;

/** Which lookahead discipline the table builder uses. */
public enum Mode {
    /** LR(0) automaton, reduce on FOLLOW sets. */
    SLR,
    /** Canonical LR(1) states merged by LR(0) core, lookaheads united. */
    LALR,
    /** Canonical LR(1). */
    LR1
}
