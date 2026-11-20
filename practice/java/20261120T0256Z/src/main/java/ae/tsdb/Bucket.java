package ae.tsdb;

/**
 * One non-empty aggregation bucket.
 *
 * @param start bucket start timestamp
 * @param count number of non-NaN points in the bucket
 * @param value the aggregate (for {@link Agg#COUNT} the count as a double)
 */
public record Bucket(long start, int count, double value) { }
