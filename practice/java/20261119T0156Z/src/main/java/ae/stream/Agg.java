package ae.stream;

/** Aggregate of values: count, sum, min and max. {@link #merge} is exact (overflow throws). */
public record Agg(long count, long sum, long min, long max) {
    public static Agg of(long value) {
        return new Agg(1, value, value, value);
    }

    public Agg merge(Agg o) {
        return new Agg(Math.addExact(count, o.count), Math.addExact(sum, o.sum),
            Math.min(min, o.min), Math.max(max, o.max));
    }
}
