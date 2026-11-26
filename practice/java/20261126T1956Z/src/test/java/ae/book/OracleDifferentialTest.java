package ae.book;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Differential test: the production book against a naive oracle that keeps one flat list and re-sorts on every match.
 */
class OracleDifferentialTest {

    /** Independent reference model; shares only the public value types with the production code. */
    static final class Oracle {
        static final class Ord {
            long id; String owner; Side side; long price; long total; long visible; long display; long seq;
        }

        final List<Ord> orders = new ArrayList<>();
        long seq = 0;

        Optional<Ord> find(long id) {
            return orders.stream().filter(o -> o.id == id).findFirst();
        }

        boolean crosses(Side takerSide, Long limit, long makerPrice) {
            if (limit == null) return true;
            return takerSide == Side.BUY ? makerPrice <= limit : makerPrice >= limit;
        }

        Comparator<Ord> matchOrder(Side makerSide) {
            Comparator<Ord> byPrice = makerSide == Side.SELL
                    ? Comparator.comparingLong(o -> o.price)
                    : Comparator.comparingLong((Ord o) -> o.price).reversed();
            return byPrice.thenComparingLong(o -> o.seq);
        }

        Result run(long id, String owner, Side side, Long limit, long qty, Tif tif, boolean post, long display,
                   boolean market) {
            List<Ord> opp = new ArrayList<>(orders.stream().filter(o -> o.side != side && crosses(side, limit, o.price)).toList());
            opp.sort(matchOrder(side.opposite()));
            if (post && !opp.isEmpty()) return new Result(Status.REJECTED, List.of(), 0, qty, "POST_ONLY");
            if (tif == Tif.FOK && !fok(opp, owner, qty)) return new Result(Status.REJECTED, List.of(), 0, qty, "FOK");
            List<Trade> trades = new ArrayList<>();
            long rem = qty;
            boolean stp = false;
            while (rem > 0) {
                List<Ord> cand = new ArrayList<>(orders.stream()
                        .filter(o -> o.side != side && crosses(side, limit, o.price)).toList());
                if (cand.isEmpty()) break;
                cand.sort(matchOrder(side.opposite()));
                Ord m = cand.get(0);
                if (m.owner.equals(owner)) { stp = true; break; }
                long q = Math.min(rem, m.visible);
                trades.add(new Trade(id, m.id, m.price, q));
                rem -= q; m.total -= q; m.visible -= q;
                if (m.total == 0) orders.remove(m);
                else if (m.visible == 0) { m.visible = Math.min(m.display, m.total); m.seq = seq++; }
            }
            long filled = qty - rem;
            if (rem == 0) return new Result(Status.FILLED, trades, filled, 0, "");
            if (stp) return new Result(Status.CANCELLED, trades, filled, rem, "SELF_TRADE");
            if (market) return new Result(Status.CANCELLED, trades, filled, rem, "MARKET");
            if (tif == Tif.IOC) return new Result(Status.CANCELLED, trades, filled, rem, "IOC");
            Ord o = new Ord();
            o.id = id; o.owner = owner; o.side = side; o.price = limit; o.total = rem; o.display = display;
            o.visible = display > 0 ? Math.min(display, rem) : rem; o.seq = seq++;
            orders.add(o);
            return new Result(Status.RESTING, trades, filled, rem, "");
        }

        boolean fok(List<Ord> sortedCrossing, String owner, long qty) {
            long cum = 0;
            List<Long> prices = sortedCrossing.stream().map(o -> o.price).distinct().toList();
            for (long p : prices) {
                List<Ord> level = sortedCrossing.stream().filter(o -> o.price == p).toList();
                if (level.stream().anyMatch(o -> o.owner.equals(owner))) return cum >= qty;
                cum += level.stream().mapToLong(o -> o.total).sum();
                if (cum >= qty) return true;
            }
            return cum >= qty;
        }

        boolean cancel(long id) {
            return orders.removeIf(o -> o.id == id);
        }

        boolean amend(long id, long n) {
            Optional<Ord> f = find(id);
            if (f.isEmpty()) return false;
            Ord o = f.get();
            if (n < o.total) { o.total = n; o.visible = Math.min(o.visible, n); }
            else if (n > o.total) {
                o.total = n; o.visible = o.display > 0 ? Math.min(o.display, n) : n; o.seq = seq++;
            }
            return true;
        }

        Result replace(long id, long p, long q) {
            Ord o = find(id).orElseThrow();
            orders.remove(o);
            return run(id, o.owner, o.side, p, q, Tif.GTC, false, o.display, false);
        }

        List<Level> depth(Side side) {
            List<Ord> side0 = new ArrayList<>(orders.stream().filter(o -> o.side == side).toList());
            side0.sort(matchOrder(side));
            List<Level> out = new ArrayList<>();
            for (Ord o : side0) {
                if (!out.isEmpty() && out.get(out.size() - 1).price() == o.price) {
                    Level l = out.remove(out.size() - 1);
                    out.add(new Level(l.price(), l.visibleQty() + o.visible, l.orders() + 1));
                } else {
                    out.add(new Level(o.price, o.visible, 1));
                }
            }
            return out;
        }
    }

    private static void runSeed(long seed, int steps, int owners, int priceSpan) {
        Random rnd = new Random(seed);
        OrderBook book = new OrderBook();
        Oracle oracle = new Oracle();
        long nextId = 1;
        List<Long> everUsed = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            String ctx = "seed " + seed + " step " + step;
            int op = rnd.nextInt(100);
            String owner = "o" + rnd.nextInt(owners);
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            long price = 100 - priceSpan + rnd.nextInt(2 * priceSpan + 1);
            long qty = 1 + rnd.nextInt(12);
            if (op < 50) {
                long id = nextId++;
                everUsed.add(id);
                Limit l = Limit.of(id, owner, side, price, qty);
                int k = rnd.nextInt(10);
                if (k == 0) l = l.tif(Tif.IOC);
                else if (k == 1) l = l.tif(Tif.FOK);
                else if (k == 2) l = l.postOnly();
                if (rnd.nextInt(4) == 0) l = l.iceberg(1 + rnd.nextInt(4));
                Result exp = oracle.run(id, owner, side, price, qty, l.tif(), l.post(), l.display(), false);
                assertEquals(exp, book.submit(l), ctx + " submit " + l);
            } else if (op < 60) {
                long id = nextId++;
                everUsed.add(id);
                Result exp = oracle.run(id, owner, side, null, qty, Tif.IOC, false, 0, true);
                assertEquals(exp, book.market(id, owner, side, qty), ctx + " market");
            } else if (op < 72) {
                long id = everUsed.isEmpty() ? 1 : everUsed.get(rnd.nextInt(everUsed.size()));
                assertEquals(oracle.cancel(id), book.cancel(id), ctx + " cancel " + id);
            } else if (op < 85) {
                long id = everUsed.isEmpty() ? 1 : everUsed.get(rnd.nextInt(everUsed.size()));
                assertEquals(oracle.amend(id, qty), book.amendQty(id, qty), ctx + " amend " + id);
            } else {
                long id = everUsed.isEmpty() ? 1 : everUsed.get(rnd.nextInt(everUsed.size()));
                if (oracle.find(id).isPresent()) {
                    assertEquals(oracle.replace(id, price, qty), book.replace(id, price, qty), ctx + " replace " + id);
                }
            }
            book.checkInvariants();
            assertEquals(oracle.depth(Side.BUY), book.depth(Side.BUY, Integer.MAX_VALUE), ctx + " bids");
            assertEquals(oracle.depth(Side.SELL), book.depth(Side.SELL, Integer.MAX_VALUE), ctx + " asks");
            assertEquals(oracle.orders.size(), book.size(), ctx + " size");
            for (long id : everUsed) {
                var o = oracle.find(id);
                assertEquals(o.isPresent() ? java.util.OptionalLong.of(o.get().total) : java.util.OptionalLong.empty(),
                        book.leaves(id), ctx + " leaves " + id);
            }
        }
    }

    @Test
    void tightBookManyOwners() {
        for (long seed = 1; seed <= 60; seed++) runSeed(seed, 250, 4, 3);
    }

    @Test
    void fewOwnersForcesSelfTradeAndFokOwnLevel() {
        for (long seed = 100; seed < 140; seed++) runSeed(seed, 250, 2, 2);
    }

    @Test
    void wideBookRarelyCrosses() {
        for (long seed = 200; seed < 220; seed++) runSeed(seed, 300, 5, 12);
    }

    @Test
    void generatorExercisesEveryOutcome() {
        // measure the outcome mix so the differential runs are not vacuous
        int[] counts = new int[Status.values().length];
        java.util.Set<String> reasons = new java.util.TreeSet<>();
        int trades = 0;
        for (long seed = 1; seed <= 40; seed++) {
            Random rnd = new Random(seed);
            OrderBook b = new OrderBook();
            for (int i = 1; i <= 250; i++) {
                Limit l = Limit.of(i, "o" + rnd.nextInt(2), rnd.nextBoolean() ? Side.BUY : Side.SELL,
                        98 + rnd.nextInt(5), 1 + rnd.nextInt(12));
                int k = rnd.nextInt(10);
                if (k == 0) l = l.tif(Tif.IOC); else if (k == 1) l = l.tif(Tif.FOK); else if (k == 2) l = l.postOnly();
                if (rnd.nextInt(4) == 0) l = l.iceberg(1 + rnd.nextInt(4));
                Result r = b.submit(l);
                counts[r.status().ordinal()]++;
                reasons.add(r.reason());
                trades += r.trades().size();
            }
        }
        for (Status s : Status.values()) assertTrue(counts[s.ordinal()] > 100, s + " only " + counts[s.ordinal()]);
        assertTrue(reasons.containsAll(List.of("", "IOC", "FOK", "POST_ONLY", "SELF_TRADE")), reasons.toString());
        assertTrue(trades > 1000, "trades " + trades);
    }

    @Test
    void conservationOfQuantity() {
        Random rnd = new Random(7);
        OrderBook b = new OrderBook();
        long submitted = 0, tradedTaker = 0, cancelledLeaves = 0;
        for (int i = 1; i <= 2000; i++) {
            long qty = 1 + rnd.nextInt(9);
            Limit l = Limit.of(i, "o" + rnd.nextInt(3), rnd.nextBoolean() ? Side.BUY : Side.SELL, 99 + rnd.nextInt(3), qty);
            Result r = b.submit(l);
            submitted += qty;
            long sum = r.trades().stream().mapToLong(Trade::qty).sum();
            assertEquals(sum, r.filled());
            assertEquals(qty, r.filled() + r.leaves());
            tradedTaker += sum;
            if (r.status() == Status.CANCELLED) cancelledLeaves += r.leaves();
            if (r.status() == Status.REJECTED) cancelledLeaves += r.leaves();
        }
        long resting = 0;
        for (int i = 1; i <= 2000; i++) resting += b.leaves(i).orElse(0);
        // each submitted unit: filled as taker, cancelled/rejected, or rested; a rested unit later traded as maker
        // is no longer in `resting`, and maker-side units traded == taker-side units traded.
        assertEquals(submitted, 2 * tradedTaker + cancelledLeaves + resting);
    }
}
