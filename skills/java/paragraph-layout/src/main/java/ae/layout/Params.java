package ae.layout;

/**
 * Tuning knobs for {@link Breaker}.
 *
 * @param linePenalty    added to every line's badness before squaring
 * @param flaggedDemerits charged when two consecutive lines both end at a flagged penalty
 * @param tolerance      largest acceptable badness of any line, at most {@link #MAX_TOLERANCE}
 */
public record Params(int linePenalty, int flaggedDemerits, long tolerance) {
    /** Cap that keeps squared demerits inside {@code long}; also the meaning of "unlimited". */
    public static final long MAX_TOLERANCE = 1_000_000L;

    public static final Params DEFAULT = new Params(10, 3000, 200);

    public Params {
        if (linePenalty < 0 || flaggedDemerits < 0) {
            throw new IllegalArgumentException("penalties must not be negative");
        }
        if (tolerance < 0 || tolerance > MAX_TOLERANCE) {
            throw new IllegalArgumentException("tolerance must be in [0, " + MAX_TOLERANCE + "]: " + tolerance);
        }
    }

    public Params withTolerance(long t) {
        return new Params(linePenalty, flaggedDemerits, t);
    }
}
