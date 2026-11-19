package ae.stream;

/** An emitted window aggregate. */
public record Result(String key, Window window, Agg agg, Kind kind) { }
