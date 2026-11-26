package ae.book;

/** One execution; {@code price} is the maker's price. */
public record Trade(long takerId, long makerId, long price, long qty) { }
