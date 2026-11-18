package ae.es.app;

import ae.es.domain.CartEvent;
import ae.es.port.EventStore;
import ae.es.port.StoredEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Query side read model: units and revenue per sku over checked-out carts, and the number of
 * open carts. Keeps a checkpoint (last global position applied) so {@link #catchUp} is
 * incremental and replaying already-applied positions is a no-op.
 */
public final class SalesProjection {
    private record Line(long quantity, long unitPriceCents) { }

    private long checkpoint;
    private final Map<String, Map<String, Line>> carts = new HashMap<>();
    private final Set<String> open = new HashSet<>();
    private final Map<String, Long> units = new TreeMap<>();
    private final Map<String, Long> revenue = new TreeMap<>();

    public long checkpoint() {
        return checkpoint;
    }

    /** Applies every event after the checkpoint; returns how many were applied. */
    public int catchUp(EventStore store) {
        List<StoredEvent> batch = store.readAll(checkpoint);
        for (StoredEvent se : batch) {
            apply(se);
        }
        return batch.size();
    }

    /** Discards all state and replays the whole store. */
    public void rebuild(EventStore store) {
        checkpoint = 0;
        carts.clear();
        open.clear();
        units.clear();
        revenue.clear();
        catchUp(store);
    }

    private void apply(StoredEvent se) {
        if (se.position() <= checkpoint) {
            return;
        }
        String id = se.streamId();
        switch (se.event()) {
            case CartEvent.CartOpened e -> {
                carts.put(id, new TreeMap<>());
                open.add(id);
            }
            case CartEvent.ItemAdded e -> {
                Map<String, Line> lines = carts.get(id);
                Line old = lines.get(e.sku());
                long q = (old == null ? 0 : old.quantity()) + e.quantity();
                lines.put(e.sku(), new Line(q, e.unitPriceCents()));
            }
            case CartEvent.ItemRemoved e -> carts.get(id).remove(e.sku());
            case CartEvent.CheckedOut e -> {
                open.remove(id);
                for (Map.Entry<String, Line> l : carts.remove(id).entrySet()) {
                    units.merge(l.getKey(), l.getValue().quantity(), Long::sum);
                    revenue.merge(l.getKey(), l.getValue().quantity() * l.getValue().unitPriceCents(), Long::sum);
                }
            }
        }
        checkpoint = se.position();
    }

    public long unitsSold(String sku) {
        return units.getOrDefault(sku, 0L);
    }

    public long revenueCents(String sku) {
        return revenue.getOrDefault(sku, 0L);
    }

    public long totalRevenueCents() {
        long t = 0;
        for (long r : revenue.values()) {
            t += r;
        }
        return t;
    }

    public int openCarts() {
        return open.size();
    }

    public Set<String> soldSkus() {
        return Set.copyOf(units.keySet());
    }

    /** Deterministic text form of the whole read model, for equality checks. */
    public String snapshot() {
        return "units=" + units + " revenue=" + revenue + " open=" + new java.util.TreeSet<>(open)
                + " checkpoint=" + checkpoint;
    }
}
