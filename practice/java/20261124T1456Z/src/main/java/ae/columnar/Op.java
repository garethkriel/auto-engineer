package ae.columnar;

/** Signed comparison operators with zone-map reasoning. */
public enum Op {
    EQ, NE, LT, LE, GT, GE;

    /** What a zone map {@code [min, max]} proves about a predicate. */
    public enum Verdict { NONE, MAYBE, ALL }

    /** Does {@code value op probe} hold? */
    public boolean test(long value, long probe) {
        return switch (this) {
            case EQ -> value == probe;
            case NE -> value != probe;
            case LT -> value < probe;
            case LE -> value <= probe;
            case GT -> value > probe;
            case GE -> value >= probe;
        };
    }

    /** Verdict for every value in {@code [min, max]} against {@code probe}; never overflows. */
    public Verdict prune(long min, long max, long probe) {
        return switch (this) {
            case EQ -> probe < min || probe > max ? Verdict.NONE : min == max ? Verdict.ALL : Verdict.MAYBE;
            case NE -> probe < min || probe > max ? Verdict.ALL : min == max ? Verdict.NONE : Verdict.MAYBE;
            case LT -> max < probe ? Verdict.ALL : min >= probe ? Verdict.NONE : Verdict.MAYBE;
            case LE -> max <= probe ? Verdict.ALL : min > probe ? Verdict.NONE : Verdict.MAYBE;
            case GT -> min > probe ? Verdict.ALL : max <= probe ? Verdict.NONE : Verdict.MAYBE;
            case GE -> min >= probe ? Verdict.ALL : max < probe ? Verdict.NONE : Verdict.MAYBE;
        };
    }
}
