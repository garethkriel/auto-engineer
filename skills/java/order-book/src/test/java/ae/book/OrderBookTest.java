package ae.book;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Hand-derived literal cases, one per rule of TASK.md. */
class OrderBookTest {
    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
    }

    private static Limit sell(long id, String owner, long price, long qty) {
        return Limit.of(id, owner, Side.SELL, price, qty);
    }

    private static Limit buy(long id, String owner, long price, long qty) {
        return Limit.of(id, owner, Side.BUY, price, qty);
    }

    @Test
    void fifoInsideLevelAndTradeAtMakerPrice() {
        book.submit(sell(1, "a", 100, 5));
        book.submit(sell(2, "b", 100, 5));
        book.submit(sell(3, "c", 99, 4));
        Result r = book.submit(buy(10, "z", 105, 12));
        // best price first (99 -> id 3), then FIFO at 100 (id 1 then id 2 partially: 4+5+3)
        assertEquals(List.of(new Trade(10, 3, 99, 4), new Trade(10, 1, 100, 5), new Trade(10, 2, 100, 3)), r.trades());
        assertEquals(Status.FILLED, r.status());
        assertEquals(12, r.filled());
        assertEquals(0, r.leaves());
        assertEquals(OptionalLong.of(2), book.leaves(2));
        assertEquals(OptionalLong.empty(), book.leaves(1));
        assertEquals(List.of(new Level(100, 2, 1)), book.depth(Side.SELL, 5));
    }

    @Test
    void partialFillRestsRemainder() {
        book.submit(sell(1, "a", 100, 4));
        Result r = book.submit(buy(2, "b", 101, 10));
        assertEquals(List.of(new Trade(2, 1, 100, 4)), r.trades());
        assertEquals(Status.RESTING, r.status());
        assertEquals(4, r.filled());
        assertEquals(6, r.leaves());
        assertEquals(OptionalLong.of(101), book.bestBid());
        assertEquals(OptionalLong.empty(), book.bestAsk());
        assertEquals(List.of(new Level(101, 6, 1)), book.depth(Side.BUY, 3));
    }

    @Test
    void nonCrossingOrderRestsWithoutTrades() {
        book.submit(sell(1, "a", 100, 4));
        Result r = book.submit(buy(2, "b", 99, 3));
        assertEquals(Status.RESTING, r.status());
        assertTrue(r.trades().isEmpty());
        assertEquals(OptionalLong.of(99), book.bestBid());
        assertEquals(OptionalLong.of(100), book.bestAsk());
    }

    @Test
    void marketSweepsLevelsAndCancelsRemainder() {
        book.submit(sell(1, "a", 100, 3));
        book.submit(sell(2, "a", 102, 3));
        Result r = book.market(9, "z", Side.BUY, 10);
        assertEquals(List.of(new Trade(9, 1, 100, 3), new Trade(9, 2, 102, 3)), r.trades());
        assertEquals(Status.CANCELLED, r.status());
        assertEquals("MARKET", r.reason());
        assertEquals(6, r.filled());
        assertEquals(4, r.leaves());
        assertEquals(0, book.size());
    }

    @Test
    void marketOnEmptyBookIsCancelledWithNoTrades() {
        Result r = book.market(1, "z", Side.SELL, 5);
        assertEquals(new Result(Status.CANCELLED, List.of(), 0, 5, "MARKET"), r);
    }

    @Test
    void iocPartialFillCancelsRemainderAndNeverRests() {
        book.submit(sell(1, "a", 100, 4));
        Result r = book.submit(buy(2, "b", 100, 10).tif(Tif.IOC));
        assertEquals(new Result(Status.CANCELLED, List.of(new Trade(2, 1, 100, 4)), 4, 6, "IOC"), r);
        assertEquals(OptionalLong.empty(), book.bestBid());
        assertEquals(OptionalLong.empty(), book.leaves(2));
    }

    @Test
    void iocFullFillIsFilled() {
        book.submit(sell(1, "a", 100, 4));
        Result r = book.submit(buy(2, "b", 100, 4).tif(Tif.IOC));
        assertEquals(Status.FILLED, r.status());
        assertEquals("", r.reason());
    }

    @Test
    void fokFillsWhenEnoughLiquidityAcrossLevels() {
        book.submit(sell(1, "a", 100, 3));
        book.submit(sell(2, "a", 101, 3));
        Result r = book.submit(buy(3, "b", 101, 5).tif(Tif.FOK));
        assertEquals(Status.FILLED, r.status());
        assertEquals(List.of(new Trade(3, 1, 100, 3), new Trade(3, 2, 101, 2)), r.trades());
    }

    @Test
    void fokRejectedLeavesBookUntouched() {
        book.submit(sell(1, "a", 100, 3));
        book.submit(sell(2, "a", 102, 3)); // beyond the limit price: must not count
        Result r = book.submit(buy(3, "b", 101, 4).tif(Tif.FOK));
        assertEquals(new Result(Status.REJECTED, List.of(), 0, 4, "FOK"), r);
        assertEquals(List.of(new Level(100, 3, 1)), book.depth(Side.SELL, 1));
        assertEquals(OptionalLong.empty(), book.leaves(3));
        assertEquals(OptionalLong.empty(), book.bestBid());
    }

    @Test
    void fokCountsHiddenIcebergQuantity() {
        book.submit(sell(1, "a", 100, 10).iceberg(2));
        Result r = book.submit(buy(2, "b", 100, 10).tif(Tif.FOK));
        assertEquals(Status.FILLED, r.status());
        assertEquals(10, r.filled());
        // slices of 2 at the same level: five trades of 2
        assertEquals(5, r.trades().size());
    }

    @Test
    void fokStopsAtLevelHoldingOwnOrderUnlessAlreadySatisfied() {
        book.submit(sell(1, "a", 100, 3));
        book.submit(sell(2, "me", 101, 5));
        // 3 available before the own level: 4 needed -> rejected although 8 is crossing
        Result r1 = book.submit(buy(3, "me", 101, 4).tif(Tif.FOK));
        assertEquals(new Result(Status.REJECTED, List.of(), 0, 4, "FOK"), r1);
        // control: only 3 needed, satisfied before the own level
        Result r2 = book.submit(buy(4, "me", 101, 3).tif(Tif.FOK));
        assertEquals(Status.FILLED, r2.status());
        assertEquals(OptionalLong.of(5), book.leaves(2));
    }

    @Test
    void postOnlyRejectedWhenItWouldCross() {
        book.submit(sell(1, "a", 100, 3));
        Result r = book.submit(buy(2, "b", 100, 3).postOnly());
        assertEquals(new Result(Status.REJECTED, List.of(), 0, 3, "POST_ONLY"), r);
        assertEquals(OptionalLong.of(3), book.leaves(1));
        assertEquals(OptionalLong.empty(), book.leaves(2));
    }

    @Test
    void postOnlyRestsOneTickAwayFromCrossing() {
        book.submit(sell(1, "a", 100, 3));
        Result r = book.submit(buy(2, "b", 99, 3).postOnly());
        assertEquals(Status.RESTING, r.status());
        assertEquals(OptionalLong.of(99), book.bestBid());
    }

    @Test
    void selfTradeStopsMatchingAfterEarlierTrades() {
        book.submit(sell(1, "other", 100, 2));
        book.submit(sell(2, "me", 100, 5));
        book.submit(sell(3, "other", 100, 4));
        Result r = book.submit(buy(4, "me", 100, 9));
        assertEquals(new Result(Status.CANCELLED, List.of(new Trade(4, 1, 100, 2)), 2, 7, "SELF_TRADE"), r);
        // own maker untouched, order behind it untouched, taker not resting
        assertEquals(OptionalLong.of(5), book.leaves(2));
        assertEquals(OptionalLong.of(4), book.leaves(3));
        assertEquals(OptionalLong.empty(), book.leaves(4));
        assertEquals(OptionalLong.empty(), book.bestBid());
    }

    @Test
    void selfTradeOnlyWhenOwnOrderIsNextNotWhenBehind() {
        book.submit(sell(1, "other", 100, 5));
        book.submit(sell(2, "me", 100, 5));
        Result r = book.submit(buy(3, "me", 100, 5));
        assertEquals(Status.FILLED, r.status());
        assertEquals(List.of(new Trade(3, 1, 100, 5)), r.trades());
    }

    @Test
    void icebergReloadsToBackOfQueue() {
        book.submit(sell(1, "a", 100, 10).iceberg(3));
        book.submit(sell(2, "b", 100, 5));
        Result r = book.submit(buy(3, "z", 100, 12).tif(Tif.IOC));
        // queue [1(vis3,tot10), 2(5)] -> 1:3 (reload, to back) -> 2:5 -> 1:3 (reload) -> 1:1
        assertEquals(List.of(new Trade(3, 1, 100, 3), new Trade(3, 2, 100, 5),
                new Trade(3, 1, 100, 3), new Trade(3, 1, 100, 1)), r.trades());
        assertEquals(Status.FILLED, r.status());
        assertEquals(OptionalLong.of(3), book.leaves(1));
        assertEquals(List.of(new Level(100, 2, 1)), book.depth(Side.SELL, 1)); // visible slice 3-1
    }

    @Test
    void depthShowsOnlyVisibleQuantityAndOrderCount() {
        book.submit(buy(1, "a", 100, 10).iceberg(4));
        book.submit(buy(2, "b", 100, 3));
        book.submit(buy(3, "c", 99, 7));
        assertEquals(List.of(new Level(100, 7, 2), new Level(99, 7, 1)), book.depth(Side.BUY, 5));
        assertEquals(List.of(new Level(100, 7, 2)), book.depth(Side.BUY, 1));
        assertEquals(List.of(), book.depth(Side.SELL, 5));
    }

    @Test
    void restingIcebergTakerShowsOnlyDisplaySlice() {
        book.submit(sell(1, "a", 100, 2));
        Result r = book.submit(buy(2, "b", 100, 9).iceberg(3));
        assertEquals(Status.RESTING, r.status());
        assertEquals(7, r.leaves());
        assertEquals(List.of(new Level(100, 3, 1)), book.depth(Side.BUY, 1));
        assertEquals(OptionalLong.of(7), book.leaves(2));
    }

    @Test
    void cancelRemovesOrderAndEmptyLevel() {
        book.submit(buy(1, "a", 100, 1));
        book.submit(buy(2, "a", 99, 1));
        assertTrue(book.cancel(1));
        assertFalse(book.cancel(1));
        assertEquals(OptionalLong.of(99), book.bestBid());
        assertFalse(book.cancel(77));
        assertEquals(1, book.size());
    }

    @Test
    void amendDownKeepsPriorityAmendUpLosesIt() {
        book.submit(sell(1, "a", 100, 5));
        book.submit(sell(2, "b", 100, 5));
        assertTrue(book.amendQty(1, 3)); // reduce: still first
        Result r1 = book.submit(buy(3, "z", 100, 1));
        assertEquals(List.of(new Trade(3, 1, 100, 1)), r1.trades());
        assertTrue(book.amendQty(1, 4)); // 2 left -> 4: goes behind order 2
        Result r2 = book.submit(buy(4, "z", 100, 1));
        assertEquals(List.of(new Trade(4, 2, 100, 1)), r2.trades());
        assertTrue(book.amendQty(1, 4)); // equal: no-op, no priority change
        assertEquals(OptionalLong.of(4), book.leaves(1));
        assertFalse(book.amendQty(99, 1));
    }

    @Test
    void amendEqualDoesNotMoveOrder() {
        book.submit(sell(1, "a", 100, 5));
        book.submit(sell(2, "b", 100, 5));
        assertTrue(book.amendQty(1, 5));
        assertEquals(List.of(new Trade(3, 1, 100, 2)), book.submit(buy(3, "z", 100, 2)).trades());
    }

    @Test
    void amendUpOfIcebergResetsVisibleSlice() {
        book.submit(sell(1, "a", 100, 4).iceberg(3));
        assertEquals(List.of(new Level(100, 3, 1)), book.depth(Side.SELL, 1));
        assertTrue(book.amendQty(1, 20));
        assertEquals(List.of(new Level(100, 3, 1)), book.depth(Side.SELL, 1));
        assertTrue(book.amendQty(1, 2)); // reduce below the slice
        assertEquals(List.of(new Level(100, 2, 1)), book.depth(Side.SELL, 1));
        book.checkInvariants();
    }

    @Test
    void replaceCanCrossAndLosesPriority() {
        book.submit(sell(1, "a", 105, 5));
        book.submit(buy(2, "b", 100, 5));
        book.submit(buy(3, "c", 100, 5));
        Result same = book.replace(2, 100, 5); // same price: goes behind 3
        assertEquals(Status.RESTING, same.status());
        assertEquals(List.of(new Trade(9, 3, 100, 5)), book.submit(sell(9, "z", 100, 5)).trades());
        Result cross = book.replace(2, 105, 8);
        assertEquals(List.of(new Trade(2, 1, 105, 5)), cross.trades());
        assertEquals(3, cross.leaves());
        assertEquals(OptionalLong.of(105), book.bestBid());
        assertThrows(NoSuchElementException.class, () -> book.replace(500, 1, 1));
    }

    @Test
    void replaceKeepsIcebergDisplayAndInvalidReplaceChangesNothing() {
        book.submit(buy(1, "a", 100, 10).iceberg(2));
        assertThrows(IllegalArgumentException.class, () -> book.replace(1, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> book.replace(1, 99, 0));
        assertEquals(OptionalLong.of(10), book.leaves(1));
        assertEquals(OptionalLong.of(100), book.bestBid());
        book.replace(1, 99, 6);
        assertEquals(List.of(new Level(99, 2, 1)), book.depth(Side.BUY, 1));
    }

    @Test
    void invalidInputThrowsWithoutSideEffects() {
        book.submit(buy(1, "a", 100, 5));
        assertThrows(IllegalArgumentException.class, () -> book.submit(buy(2, "a", 100, 0)));
        assertThrows(IllegalArgumentException.class, () -> book.submit(buy(2, "a", 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> book.submit(buy(2, " ", 100, 1)));
        assertThrows(IllegalArgumentException.class, () -> book.submit(buy(2, "a", 100, 1).iceberg(-1)));
        assertThrows(IllegalArgumentException.class, () -> book.submit(buy(1, "b", 90, 1))); // live id
        assertThrows(IllegalArgumentException.class, () -> book.market(3, "a", Side.SELL, 0));
        assertThrows(IllegalArgumentException.class, () -> book.market(1, "a", Side.SELL, 1)); // live id
        assertThrows(NullPointerException.class, () -> book.submit(null));
        assertThrows(NullPointerException.class, () -> book.market(3, null, Side.SELL, 1));
        assertThrows(IllegalArgumentException.class, () -> book.amendQty(1, 0));
        assertEquals(1, book.size());
        assertEquals(OptionalLong.of(5), book.leaves(1));
    }

    @Test
    void idCanBeReusedAfterFillOrCancel() {
        book.submit(sell(1, "a", 100, 1));
        book.submit(buy(2, "b", 100, 1));
        assertEquals(Status.RESTING, book.submit(sell(1, "a", 100, 1)).status());
        assertTrue(book.cancel(1));
        assertEquals(Status.RESTING, book.submit(sell(1, "a", 100, 1)).status());
    }

    @Test
    void depthRejectsNegativeLevelsAndZeroReturnsEmpty() {
        book.submit(buy(1, "a", 100, 1));
        assertEquals(List.of(), book.depth(Side.BUY, 0));
        assertThrows(IllegalArgumentException.class, () -> book.depth(Side.BUY, -1));
    }

    @Test
    void sellTakerMatchesHighestBidFirst() {
        book.submit(buy(1, "a", 99, 2));
        book.submit(buy(2, "b", 101, 2));
        Result r = book.submit(sell(3, "c", 99, 3));
        assertEquals(List.of(new Trade(3, 2, 101, 2), new Trade(3, 1, 99, 1)), r.trades());
    }
}
