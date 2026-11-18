package ae.es.domain;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable event-sourced cart aggregate. {@code decide*} validates a command against the
 * current state and returns the events it produces (it never mutates); {@link #apply} folds
 * one event into a new state.
 */
public final class Cart {
    private enum Status { NEW, OPEN, CHECKED_OUT }

    private record Line(int quantity, int unitPriceCents) { }

    private static final Cart EMPTY = new Cart(Status.NEW, Collections.emptyMap());

    private final Status status;
    private final Map<String, Line> lines;

    private Cart(Status status, Map<String, Line> lines) {
        this.status = status;
        this.lines = lines;
    }

    public static Cart empty() {
        return EMPTY;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public boolean isCheckedOut() {
        return status == Status.CHECKED_OUT;
    }

    public long quantity(String sku) {
        Line l = lines.get(sku);
        return l == null ? 0 : l.quantity();
    }

    public int unitPrice(String sku) {
        Line l = lines.get(sku);
        if (l == null) {
            throw new IllegalArgumentException("unknown sku " + sku);
        }
        return l.unitPriceCents();
    }

    public long totalCents() {
        long total = 0;
        for (Line l : lines.values()) {
            total = Math.addExact(total, Math.multiplyExact((long) l.quantity(), l.unitPriceCents()));
        }
        return total;
    }

    public List<CartEvent> decideOpen() {
        if (status != Status.NEW) {
            throw new IllegalStateException("cart already opened");
        }
        return List.of(new CartEvent.CartOpened());
    }

    public List<CartEvent> decideAdd(String sku, int quantity, int unitPriceCents) {
        requireOpen();
        if (sku == null || sku.isEmpty()) {
            throw new IllegalArgumentException("sku must not be empty");
        }
        if (quantity <= 0 || unitPriceCents <= 0) {
            throw new IllegalArgumentException("quantity and price must be positive");
        }
        Line existing = lines.get(sku);
        if (existing != null && existing.unitPriceCents() != unitPriceCents) {
            throw new IllegalArgumentException("sku " + sku + " already has another unit price");
        }
        // Compute the would-be total now so overflow is reported before any event exists.
        Cart next = apply(new CartEvent.ItemAdded(sku, quantity, unitPriceCents));
        next.totalCents();
        return List.of(new CartEvent.ItemAdded(sku, quantity, unitPriceCents));
    }

    public List<CartEvent> decideRemove(String sku) {
        requireOpen();
        if (!lines.containsKey(sku)) {
            throw new IllegalArgumentException("sku not in cart: " + sku);
        }
        return List.of(new CartEvent.ItemRemoved(sku));
    }

    public List<CartEvent> decideCheckout() {
        requireOpen();
        if (lines.isEmpty()) {
            throw new IllegalStateException("cannot check out an empty cart");
        }
        return List.of(new CartEvent.CheckedOut(totalCents()));
    }

    /** Folds one event into a new aggregate state; the receiver is unchanged. */
    public Cart apply(CartEvent event) {
        return switch (event) {
            case CartEvent.CartOpened e -> new Cart(Status.OPEN, lines);
            case CartEvent.ItemAdded e -> {
                Map<String, Line> m = new TreeMap<>(lines);
                Line old = m.get(e.sku());
                int qty = old == null ? e.quantity() : Math.addExact(old.quantity(), e.quantity());
                m.put(e.sku(), new Line(qty, e.unitPriceCents()));
                yield new Cart(status, Collections.unmodifiableMap(m));
            }
            case CartEvent.ItemRemoved e -> {
                Map<String, Line> m = new TreeMap<>(lines);
                m.remove(e.sku());
                yield new Cart(status, Collections.unmodifiableMap(m));
            }
            case CartEvent.CheckedOut e -> new Cart(Status.CHECKED_OUT, lines);
        };
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new IllegalStateException("cart is not open (" + status + ")");
        }
    }
}
