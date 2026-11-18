package ae.es.domain;

/** Facts that happened to a cart. Events are immutable and never rejected once stored. */
public sealed interface CartEvent {
    /** The cart was opened. */
    record CartOpened() implements CartEvent { }

    /** {@code quantity} units of {@code sku} were added at {@code unitPriceCents} each. */
    record ItemAdded(String sku, int quantity, int unitPriceCents) implements CartEvent { }

    /** Every unit of {@code sku} was removed. */
    record ItemRemoved(String sku) implements CartEvent { }

    /** The cart was checked out for {@code totalCents}. */
    record CheckedOut(long totalCents) implements CartEvent { }
}
