package ae.book;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Single-instrument limit order book with price-time priority, iceberg orders, IOC/FOK/post-only, self-trade
 * prevention (cancel the taker's remainder), quantity amendment and cancel/replace. See TASK.md for the rules.
 *
 * <p>Each price level is a {@link LinkedHashMap} keyed by order id: insertion order is time priority, removal by id is
 * O(1) and "move to the back" is remove + put. Not thread-safe.
 */
public final class OrderBook {

    private static final class Resting {
        final long id;
        final String owner;
        final Side side;
        final long price;
        final long display;
        long total;
        long visible;

        Resting(long id, String owner, Side side, long price, long total, long display) {
            this.id = id;
            this.owner = owner;
            this.side = side;
            this.price = price;
            this.display = display;
            this.total = total;
            this.visible = sliceFor(display, total);
        }
    }

    private static long sliceFor(long display, long total) {
        return display > 0 ? Math.min(display, total) : total;
    }

    private final TreeMap<Long, LinkedHashMap<Long, Resting>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, LinkedHashMap<Long, Resting>> asks = new TreeMap<>();
    private final Map<Long, Resting> byId = new HashMap<>();

    private TreeMap<Long, LinkedHashMap<Long, Resting>> sideMap(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** Submits a limit order. */
    public Result submit(Limit o) {
        Objects.requireNonNull(o, "order");
        Objects.requireNonNull(o.owner(), "owner");
        Objects.requireNonNull(o.side(), "side");
        Objects.requireNonNull(o.tif(), "tif");
        if (o.price() <= 0) throw new IllegalArgumentException("price must be positive");
        return execute(o.id(), o.owner(), o.side(), o.price(), o.qty(), o.tif(), o.post(), o.display(), false);
    }

    /** Submits a market order: matches at any price, a remainder is cancelled. */
    public Result market(long id, String owner, Side side, long qty) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(side, "side");
        return execute(id, owner, side, 0, qty, Tif.IOC, false, 0, true);
    }

    private Result execute(long id, String owner, Side side, long limit, long qty, Tif tif, boolean postOnly,
                           long display, boolean market) {
        if (owner.isBlank()) throw new IllegalArgumentException("owner must not be blank");
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        if (display < 0) throw new IllegalArgumentException("display must not be negative");
        if (byId.containsKey(id)) throw new IllegalArgumentException("order id is live: " + id);
        TreeMap<Long, LinkedHashMap<Long, Resting>> opp = sideMap(side.opposite());

        if (postOnly && !opp.isEmpty() && crosses(side, limit, opp.firstKey())) {
            return new Result(Status.REJECTED, List.of(), 0, qty, "POST_ONLY");
        }
        if (tif == Tif.FOK && !fillable(side, limit, market, owner, qty)) {
            return new Result(Status.REJECTED, List.of(), 0, qty, "FOK");
        }

        List<Trade> trades = new ArrayList<>();
        long rem = qty;
        boolean selfTrade = false;
        while (rem > 0 && !opp.isEmpty()) {
            Map.Entry<Long, LinkedHashMap<Long, Resting>> e = opp.firstEntry();
            long price = e.getKey();
            if (!market && !crosses(side, limit, price)) break;
            LinkedHashMap<Long, Resting> level = e.getValue();
            Resting maker = level.values().iterator().next();
            if (maker.owner.equals(owner)) {
                selfTrade = true;
                break;
            }
            long q = Math.min(rem, maker.visible);
            trades.add(new Trade(id, maker.id, price, q));
            rem -= q;
            maker.total -= q;
            maker.visible -= q;
            if (maker.total == 0) {
                level.remove(maker.id);
                byId.remove(maker.id);
            } else if (maker.visible == 0) {
                maker.visible = sliceFor(maker.display, maker.total);
                level.remove(maker.id);
                level.put(maker.id, maker); // reloaded slice goes to the back
            }
            if (level.isEmpty()) opp.remove(price);
        }

        long filled = qty - rem;
        if (rem == 0) return new Result(Status.FILLED, trades, filled, 0, "");
        if (selfTrade) return new Result(Status.CANCELLED, trades, filled, rem, "SELF_TRADE");
        if (market) return new Result(Status.CANCELLED, trades, filled, rem, "MARKET");
        if (tif == Tif.IOC) return new Result(Status.CANCELLED, trades, filled, rem, "IOC");
        rest(new Resting(id, owner, side, limit, rem, display));
        return new Result(Status.RESTING, trades, filled, rem, "");
    }

    private void rest(Resting r) {
        sideMap(r.side).computeIfAbsent(r.price, p -> new LinkedHashMap<>()).put(r.id, r);
        byId.put(r.id, r);
    }

    private static boolean crosses(Side takerSide, long limit, long makerPrice) {
        return takerSide == Side.BUY ? makerPrice <= limit : makerPrice >= limit;
    }

    /** FOK scan (TASK.md rule 6); read-only. */
    private boolean fillable(Side side, long limit, boolean market, String owner, long qty) {
        long cumulative = 0;
        for (Map.Entry<Long, LinkedHashMap<Long, Resting>> e : sideMap(side.opposite()).entrySet()) {
            if (!market && !crosses(side, limit, e.getKey())) break;
            long levelTotal = 0;
            for (Resting r : e.getValue().values()) {
                // cumulative < qty here (we return as soon as it reaches qty), so an own order ends the scan unfillable
                if (r.owner.equals(owner)) return false;
                levelTotal += r.total;
            }
            cumulative += levelTotal;
            if (cumulative >= qty) return true;
        }
        return cumulative >= qty;
    }

    /** Cancels a live order; false if unknown. */
    public boolean cancel(long id) {
        Resting r = byId.get(id);
        if (r == null) return false;
        remove(r);
        return true;
    }

    private void remove(Resting r) {
        TreeMap<Long, LinkedHashMap<Long, Resting>> m = sideMap(r.side);
        LinkedHashMap<Long, Resting> level = m.get(r.price);
        level.remove(r.id);
        if (level.isEmpty()) m.remove(r.price);
        byId.remove(r.id);
    }

    /** Changes the total quantity; reducing keeps priority, increasing sends the order to the back. */
    public boolean amendQty(long id, long newQty) {
        if (newQty <= 0) throw new IllegalArgumentException("qty must be positive");
        Resting r = byId.get(id);
        if (r == null) return false;
        if (newQty < r.total) {
            r.total = newQty;
            r.visible = Math.min(r.visible, newQty);
        } else if (newQty > r.total) {
            LinkedHashMap<Long, Resting> level = sideMap(r.side).get(r.price);
            level.remove(r.id);
            r.total = newQty;
            r.visible = sliceFor(r.display, newQty);
            level.put(r.id, r);
        }
        return true;
    }

    /** Cancel + fresh GTC submit with the same id, owner, side and display; may trade. */
    public Result replace(long id, long newPrice, long newQty) {
        Resting r = byId.get(id);
        if (r == null) throw new NoSuchElementException("unknown order " + id);
        if (newPrice <= 0) throw new IllegalArgumentException("price must be positive");
        if (newQty <= 0) throw new IllegalArgumentException("qty must be positive");
        remove(r);
        return execute(id, r.owner, r.side, newPrice, newQty, Tif.GTC, false, r.display, false);
    }

    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    /** Total (visible + hidden) remaining quantity of a live order. */
    public OptionalLong leaves(long id) {
        Resting r = byId.get(id);
        return r == null ? OptionalLong.empty() : OptionalLong.of(r.total);
    }

    /** Number of live orders. */
    public int size() {
        return byId.size();
    }

    /** Up to {@code levels} price levels, best first, counting visible quantity only. */
    public List<Level> depth(Side side, int levels) {
        if (levels < 0) throw new IllegalArgumentException("levels must not be negative");
        List<Level> out = new ArrayList<>();
        for (Map.Entry<Long, LinkedHashMap<Long, Resting>> e : sideMap(side).entrySet()) {
            if (out.size() >= levels) break;
            long qty = 0;
            for (Resting r : e.getValue().values()) qty += r.visible;
            out.add(new Level(e.getKey(), qty, e.getValue().size()));
        }
        return Collections.unmodifiableList(out);
    }

    /** Verifies the structural invariants; throws {@link IllegalStateException} naming the broken rule. */
    public void checkInvariants() {
        int count = 0;
        for (Side side : Side.values()) {
            for (Map.Entry<Long, LinkedHashMap<Long, Resting>> e : sideMap(side).entrySet()) {
                if (e.getValue().isEmpty()) throw new IllegalStateException("empty level " + e.getKey());
                for (Map.Entry<Long, Resting> oe : e.getValue().entrySet()) {
                    Resting r = oe.getValue();
                    count++;
                    if (r.id != oe.getKey()) throw new IllegalStateException("key/id mismatch " + r.id);
                    if (r.side != side || r.price != e.getKey()) throw new IllegalStateException("misfiled " + r.id);
                    if (byId.get(r.id) != r) throw new IllegalStateException("index mismatch " + r.id);
                    if (r.total <= 0) throw new IllegalStateException("non-positive total " + r.id);
                    if (r.visible <= 0 || r.visible > r.total) throw new IllegalStateException("bad visible " + r.id);
                    if (r.display == 0 && r.visible != r.total) throw new IllegalStateException("hidden plain " + r.id);
                    if (r.display > 0 && r.visible > r.display) throw new IllegalStateException("over display " + r.id);
                }
            }
        }
        if (count != byId.size()) throw new IllegalStateException("index size " + byId.size() + " vs " + count);
        if (!bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey()) {
            throw new IllegalStateException("crossed book " + bids.firstKey() + " >= " + asks.firstKey());
        }
    }
}
