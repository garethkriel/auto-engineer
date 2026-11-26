package ae.book;

/** Aggregated visible depth at one price. */
public record Level(long price, long visibleQty, int orders) { }
