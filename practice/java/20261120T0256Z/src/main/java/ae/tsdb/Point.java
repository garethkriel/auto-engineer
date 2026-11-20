package ae.tsdb;

/**
 * One sample. Equality follows {@link Double#compare}, so NaN equals NaN and 0.0 differs from -0.0.
 *
 * @param ts non-negative timestamp
 * @param value sample value
 */
public record Point(long ts, double value) { }
