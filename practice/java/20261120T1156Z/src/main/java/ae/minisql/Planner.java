package ae.minisql;

/** Execution strategy. Both must produce identical results; OPTIMIZED is the one under test. */
public enum Planner {
    /** Nested-loop joins; every WHERE conjunct is applied after all joins. */
    NAIVE,
    /** Hash joins on equi-conditions and WHERE predicate pushdown where it is provably safe. */
    OPTIMIZED
}
