package ae.es;

import static org.junit.jupiter.api.Assertions.*;

import ae.es.adapter.InMemoryEventStore;
import ae.es.app.CartService;
import ae.es.app.SalesProjection;
import ae.es.domain.Cart;
import ae.es.domain.CartEvent;
import ae.es.port.ConcurrencyException;
import ae.es.port.EventStore;
import ae.es.port.StoredEvent;
import java.util.*;
import org.junit.jupiter.api.Test;

class ServiceAndProjectionTest {

    @Test
    void serviceHappyPathPersistsEventsInOrder() {
        EventStore store = new InMemoryEventStore();
        CartService svc = new CartService(store);
        svc.open("c1");
        svc.add("c1", "pen", 3, 120);
        svc.add("c1", "ink", 1, 900);
        svc.remove("c1", "pen");
        svc.add("c1", "pen", 2, 130);
        svc.checkout("c1");
        List<CartEvent> ev = store.read("c1").stream().map(StoredEvent::event).toList();
        assertEquals(List.of(
                new CartEvent.CartOpened(),
                new CartEvent.ItemAdded("pen", 3, 120),
                new CartEvent.ItemAdded("ink", 1, 900),
                new CartEvent.ItemRemoved("pen"),
                new CartEvent.ItemAdded("pen", 2, 130),
                new CartEvent.CheckedOut(900 + 260)), ev);
        assertEquals(1160, svc.load("c1").totalCents());
    }

    @Test
    void rejectedCommandAppendsNothing() {
        EventStore store = new InMemoryEventStore();
        CartService svc = new CartService(store);
        assertThrows(IllegalStateException.class, () -> svc.add("ghost", "a", 1, 1));
        svc.open("c1");
        assertThrows(IllegalArgumentException.class, () -> svc.add("c1", "a", 0, 5));
        assertThrows(IllegalStateException.class, () -> svc.checkout("c1"));
        assertEquals(1, store.readAll(0).size());
    }

    @Test
    void serviceUsesStoreOnlyThroughPort() {
        // A recording decorator proves the service works with any EventStore implementation.
        InMemoryEventStore inner = new InMemoryEventStore();
        List<String> calls = new ArrayList<>();
        EventStore spy = new EventStore() {
            public void append(String id, long v, List<? extends CartEvent> e) {
                calls.add("append " + id + "@" + v + "x" + e.size());
                inner.append(id, v, e);
            }
            public List<StoredEvent> read(String id) {
                calls.add("read " + id);
                return inner.read(id);
            }
            public List<StoredEvent> readAll(long from) {
                return inner.readAll(from);
            }
        };
        CartService svc = new CartService(spy);
        svc.open("c1");
        svc.add("c1", "a", 1, 5);
        assertEquals(List.of("read c1", "append c1@0x1", "read c1", "append c1@1x1"), calls);
    }

    @Test
    void staleServiceCommandSurfacesConcurrencyException() {
        InMemoryEventStore store = new InMemoryEventStore();
        // A store that lets another writer sneak in between our read and our append.
        EventStore racing = new EventStore() {
            boolean armed = true;
            public void append(String id, long v, List<? extends CartEvent> e) {
                if (armed) {
                    armed = false;
                    store.append(id, v, List.of(new CartEvent.ItemAdded("x", 1, 1)));
                }
                store.append(id, v, e);
            }
            public List<StoredEvent> read(String id) { return store.read(id); }
            public List<StoredEvent> readAll(long from) { return store.readAll(from); }
        };
        new CartService(store).open("c1");
        CartService svc = new CartService(racing);
        ConcurrencyException ex = assertThrows(ConcurrencyException.class, () -> svc.add("c1", "a", 1, 5));
        assertEquals(2, ex.actualVersion());
        assertEquals(2, store.read("c1").size());
    }

    private static CartService scenario(EventStore store) {
        CartService svc = new CartService(store);
        svc.open("c1");
        svc.open("c2");
        svc.open("c3");
        svc.add("c1", "pen", 2, 100);
        svc.add("c2", "pen", 1, 100);
        svc.add("c2", "ink", 4, 250);
        svc.add("c3", "cap", 1, 50);
        svc.checkout("c1");
        svc.remove("c2", "pen");
        svc.checkout("c2");
        return svc;
    }

    @Test
    void projectionReportsUnitsRevenueAndOpenCarts() {
        EventStore store = new InMemoryEventStore();
        scenario(store);
        SalesProjection p = new SalesProjection();
        p.catchUp(store);
        assertEquals(2, p.unitsSold("pen"), "only c1's two pens were checked out");
        assertEquals(200, p.revenueCents("pen"));
        assertEquals(4, p.unitsSold("ink"));
        assertEquals(1000, p.revenueCents("ink"));
        assertEquals(0, p.unitsSold("cap"));
        assertEquals(1, p.openCarts());
        assertEquals(1200, p.totalRevenueCents());
        assertEquals(new TreeSet<>(List.of("ink", "pen")), new TreeSet<>(p.soldSkus()));
    }

    @Test
    void catchUpIsIncrementalAndIdempotent() {
        EventStore store = new InMemoryEventStore();
        CartService svc = new CartService(store);
        SalesProjection p = new SalesProjection();
        assertEquals(0, p.checkpoint());
        svc.open("c1");
        svc.add("c1", "pen", 2, 100);
        assertEquals(2, p.catchUp(store));
        assertEquals(2, p.checkpoint());
        assertEquals(0, p.catchUp(store), "second call with no new events processes nothing");
        assertEquals(1, p.openCarts());
        svc.checkout("c1");
        assertEquals(1, p.catchUp(store));
        assertEquals(0, p.openCarts());
        assertEquals(200, p.totalRevenueCents());
        assertEquals(3, p.checkpoint());
    }

    @Test
    void rebuildEqualsIncrementalAndResetsState() {
        EventStore store = new InMemoryEventStore();
        scenario(store);
        SalesProjection inc = new SalesProjection();
        inc.catchUp(store);
        SalesProjection reb = new SalesProjection();
        reb.catchUp(store);
        reb.rebuild(store);
        assertEquals(inc.snapshot(), reb.snapshot());
        assertEquals(store.readAll(0).size(), reb.checkpoint());
    }

    @Test
    void randomisedProjectionMatchesNaiveRecomputeFromAggregates() {
        String[] skus = {"a", "b", "c", "d"};
        int[] price = {100, 250, 75, 1000};
        for (long seed = 1; seed <= 60; seed++) {
            Random rnd = new Random(seed);
            EventStore store = new InMemoryEventStore();
            CartService svc = new CartService(store);
            SalesProjection live = new SalesProjection();
            List<String> ids = new ArrayList<>();
            for (int step = 0; step < 120; step++) {
                int op = rnd.nextInt(10);
                try {
                    if (op == 0 || ids.isEmpty()) {
                        String id = "cart" + ids.size();
                        svc.open(id);
                        ids.add(id);
                    } else {
                        String id = ids.get(rnd.nextInt(ids.size()));
                        int k = rnd.nextInt(skus.length);
                        if (op <= 6) svc.add(id, skus[k], 1 + rnd.nextInt(3), price[k]);
                        else if (op <= 8) svc.remove(id, skus[k]);
                        else svc.checkout(id);
                    }
                } catch (IllegalStateException | IllegalArgumentException expected) {
                    // rejected commands must leave no trace; checked via the oracle below
                }
                if (rnd.nextInt(7) == 0) live.catchUp(store);
            }
            live.catchUp(store);

            Map<String, Long> units = new TreeMap<>();
            Map<String, Long> revenue = new TreeMap<>();
            int open = 0;
            for (String id : ids) {
                Cart c = svc.load(id);
                if (c.isOpen()) open++;
                if (c.isCheckedOut()) {
                    for (String sku : skus) {
                        long q = c.quantity(sku);
                        if (q > 0) {
                            units.merge(sku, q, Long::sum);
                            revenue.merge(sku, q * c.unitPrice(sku), Long::sum);
                        }
                    }
                }
            }
            for (String sku : skus) {
                assertEquals(units.getOrDefault(sku, 0L), live.unitsSold(sku), "units " + sku + " seed " + seed);
                assertEquals(revenue.getOrDefault(sku, 0L), live.revenueCents(sku), "rev " + sku + " seed " + seed);
            }
            assertEquals(open, live.openCarts(), "open carts seed " + seed);
            SalesProjection fresh = new SalesProjection();
            fresh.catchUp(store);
            assertEquals(fresh.snapshot(), live.snapshot(), "incremental vs fresh seed " + seed);
        }
    }
}
