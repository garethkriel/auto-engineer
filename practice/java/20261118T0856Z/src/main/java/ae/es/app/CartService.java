package ae.es.app;

import ae.es.domain.Cart;
import ae.es.domain.CartEvent;
import ae.es.port.EventStore;
import ae.es.port.StoredEvent;
import java.util.List;
import java.util.Objects;

/** Command side: load stream, fold to state, decide, append with the version that was read. */
public final class CartService {
    private final EventStore store;

    public CartService(EventStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void open(String cartId) {
        handle(cartId, Cart::decideOpen);
    }

    public void add(String cartId, String sku, int quantity, int unitPriceCents) {
        handle(cartId, c -> c.decideAdd(sku, quantity, unitPriceCents));
    }

    public void remove(String cartId, String sku) {
        handle(cartId, c -> c.decideRemove(sku));
    }

    public void checkout(String cartId) {
        handle(cartId, Cart::decideCheckout);
    }

    /** Current state of a cart (empty state if the stream is unknown). */
    public Cart load(String cartId) {
        return fold(store.read(cartId));
    }

    private interface Decision {
        List<CartEvent> decide(Cart cart);
    }

    private void handle(String cartId, Decision decision) {
        Objects.requireNonNull(cartId, "cartId");
        List<StoredEvent> history = store.read(cartId);
        List<CartEvent> events = decision.decide(fold(history));
        store.append(cartId, history.size(), events);
    }

    private static Cart fold(List<StoredEvent> history) {
        Cart cart = Cart.empty();
        for (StoredEvent se : history) {
            cart = cart.apply(se.event());
        }
        return cart;
    }
}
