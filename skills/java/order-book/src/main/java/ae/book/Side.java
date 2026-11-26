package ae.book;

/** Order side. */
public enum Side {
    BUY, SELL;

    /** The side an order of this side trades against. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
