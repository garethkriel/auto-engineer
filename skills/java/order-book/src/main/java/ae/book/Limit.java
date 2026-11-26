package ae.book;

/** Immutable limit-order request. {@code post} = post-only; {@code display == 0} means fully visible. */
public record Limit(long id, String owner, Side side, long price, long qty, Tif tif, boolean post, long display) {

    /** A plain GTC, visible, non-post-only limit order. */
    public static Limit of(long id, String owner, Side side, long price, long qty) {
        return new Limit(id, owner, side, price, qty, Tif.GTC, false, 0);
    }

    public Limit tif(Tif t) {
        return new Limit(id, owner, side, price, qty, t, post, display);
    }

    public Limit postOnly() {
        return new Limit(id, owner, side, price, qty, tif, true, display);
    }

    public Limit iceberg(long displayQty) {
        return new Limit(id, owner, side, price, qty, tif, post, displayQty);
    }
}
