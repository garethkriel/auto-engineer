package ae.tsblock;

/** One sample of a time series: a timestamp and a 64-bit value. */
public record Point(long ts, long value) {}
