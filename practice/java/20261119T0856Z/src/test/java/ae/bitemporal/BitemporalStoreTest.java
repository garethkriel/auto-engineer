package ae.bitemporal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BitemporalStoreTest {

    private long now;
    private BitemporalStore<String, String> store;

    @BeforeEach
    void setUp() {
        now = 100;
        store = new BitemporalStore<>(() -> now);
    }

    private static Segment<String> seg(long from, long to, String v) {
        return new Segment<>(from, to, v);
    }

    @Test
    void newStoreIsEmptyAtTxZero() {
        assertEquals(0, store.currentTx());
        assertEquals(Optional.empty(), store.valueAt("p", 5));
        assertTrue(store.history("p").isEmpty());
        assertTrue(store.snapshot(5, 0).isEmpty());
    }

    @Test
    void assertReturnsIncreasingTxIds() {
        assertEquals(1, store.assertValue("p", 0, 10, "A"));
        assertEquals(2, store.assertValue("p", 3, 6, "B"));
        assertEquals(2, store.currentTx());
    }

    @Test
    void overwriteSplitsAndOldKnowledgeSurvives() {
        store.assertValue("p", 0, 10, "A");
        store.assertValue("p", 3, 6, "B");
        assertEquals(List.of(seg(0, 3, "A"), seg(3, 6, "B"), seg(6, 10, "A")), store.history("p"));
        assertEquals(List.of(seg(0, 10, "A")), store.history("p", 1));
        assertEquals(Optional.of("B"), store.valueAt("p", 4, 2));
        assertEquals(Optional.of("A"), store.valueAt("p", 4, 1));
        assertEquals(Optional.empty(), store.valueAt("p", 4, 0));
    }

    @Test
    void retractRemovesOnlyTheGivenRange() {
        store.assertValue("p", 0, 10, "A");
        assertEquals(2, store.retract("p", 4, 6));
        assertEquals(List.of(seg(0, 4, "A"), seg(6, 10, "A")), store.history("p"));
        assertEquals(Optional.empty(), store.valueAt("p", 5));
        assertEquals(Optional.of("A"), store.valueAt("p", 5, 1));
    }

    @Test
    void foreverIntervalsAreSupported() {
        store.assertValue("p", 5, Segment.FOREVER, "X");
        store.retract("p", 100, Segment.FOREVER);
        assertEquals(List.of(seg(5, 100, "X")), store.history("p"));
        assertEquals(Optional.of("X"), store.valueAt("p", 1_000_000, 1));
    }

    @Test
    void noOpAssertIsNotRecorded() {
        store.assertValue("p", 0, 10, "A");
        assertEquals(1, store.assertValue("p", 2, 4, "A"));
        assertEquals(1, store.retract("p", 20, 30));
        assertEquals(1, store.retract("q", 0, 5));
        assertEquals(1, store.currentTx());
    }

    @Test
    void emptyTransactionIsNotRecorded() {
        store.assertValue("p", 0, 10, "A");
        assertEquals(1, store.transact(tx -> { }));
        assertEquals(1, store.currentTx());
    }

    @Test
    void transactionWhoseOperationsCancelOutIsNotRecorded() {
        store.assertValue("p", 0, 10, "A");
        long tx = store.transact(t -> {
            t.assertValue("p", 3, 6, "B");
            t.assertValue("p", 3, 6, "A");
        });
        assertEquals(1, tx);
        assertEquals(1, store.currentTx());
        assertEquals(List.of(seg(0, 10, "A")), store.history("p"));
    }

    @Test
    void multiKeyTransactionGetsOneTxIdAndLaterOpsSeeEarlierOnes() {
        long tx = store.transact(t -> {
            t.assertValue("a", 0, 5, "1");
            t.assertValue("b", 0, 5, "2");
            t.retract("a", 0, 2);
        });
        assertEquals(1, tx);
        assertEquals(List.of(seg(2, 5, "1")), store.history("a", 1));
        assertEquals(Map.of("a", "1", "b", "2"), store.snapshot(3, 1));
        assertEquals(Map.of("b", "2"), store.snapshot(0, 1));
    }

    @Test
    void failingTransactionCommitsNothing() {
        store.assertValue("p", 0, 10, "A");
        assertThrows(IllegalStateException.class, () -> store.transact(t -> {
            t.assertValue("p", 0, 10, "Z");
            t.assertValue("q", 0, 10, "Y");
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, store.currentTx());
        assertEquals(List.of(seg(0, 10, "A")), store.history("p"));
        assertTrue(store.history("q").isEmpty());
    }

    @Test
    void invalidOperationInsideTransactionCommitsNothing() {
        assertThrows(IllegalArgumentException.class, () -> store.transact(t -> {
            t.assertValue("p", 0, 10, "A");
            t.retract("p", 7, 7);
        }));
        assertEquals(0, store.currentTx());
        assertTrue(store.history("p").isEmpty());
    }

    @Test
    void txHandleIsDeadAfterTransactionReturns() {
        @SuppressWarnings("unchecked")
        Tx<String, String>[] leaked = new Tx[1];
        store.transact(t -> {
            leaked[0] = t;
            t.assertValue("p", 0, 1, "A");
        });
        assertThrows(IllegalStateException.class, () -> leaked[0].assertValue("p", 5, 6, "B"));
        assertThrows(IllegalStateException.class, () -> leaked[0].retract("p", 0, 1));
        assertEquals(List.of(seg(0, 1, "A")), store.history("p"));
    }

    @Test
    void backwardsClockIsRejectedAndNothingIsRecorded() {
        store.assertValue("p", 0, 10, "A");
        now = 90;
        assertThrows(IllegalStateException.class, () -> store.assertValue("p", 0, 10, "B"));
        assertEquals(1, store.currentTx());
        assertEquals(List.of(seg(0, 10, "A")), store.history("p"));
        now = 100;
        assertEquals(2, store.assertValue("p", 0, 10, "B"));
    }

    @Test
    void txAtTimeFindsLatestTxNotAfterTimestamp() {
        now = 100;
        store.assertValue("p", 0, 1, "A");
        now = 100;
        store.assertValue("p", 1, 2, "A");
        now = 150;
        store.assertValue("p", 2, 3, "A");
        assertEquals(0, store.txAtTime(99));
        assertEquals(2, store.txAtTime(100));
        assertEquals(2, store.txAtTime(149));
        assertEquals(3, store.txAtTime(150));
        assertEquals(3, store.txAtTime(Long.MAX_VALUE));
        assertEquals(0, new BitemporalStore<String, String>(() -> 0).txAtTime(5));
    }

    @Test
    void snapshotIsSortedByKey() {
        store.assertValue("b", 0, 10, "2");
        store.assertValue("c", 0, 10, "3");
        store.assertValue("a", 5, 10, "1");
        assertEquals(List.of("b", "c"), List.copyOf(store.snapshot(1, 3).keySet()));
        assertEquals(List.of("a", "b", "c"), List.copyOf(store.snapshot(7, 3).keySet()));
        assertEquals(List.of("b", "c"), List.copyOf(store.snapshot(7, 2).keySet()));
        assertEquals(List.of(), List.copyOf(store.snapshot(10, 3).keySet()));
    }

    @Test
    void diffListsOnlyDifferingPieces() {
        store.assertValue("p", 0, 10, "A");
        store.assertValue("p", 3, 6, "B");
        store.retract("p", 8, 10);
        assertEquals(List.of(new Delta<>(3, 6, "A", "B"), new Delta<>(8, 10, "A", null)),
                store.diff("p", 1, 3));
        assertEquals(List.of(new Delta<>(3, 6, "B", "A"), new Delta<>(8, 10, null, "A")),
                store.diff("p", 3, 1));
        assertEquals(List.of(new Delta<>(8, 10, "A", null)), store.diff("p", 2, 3));
        assertTrue(store.diff("p", 2, 2).isEmpty());
        assertEquals(List.of(new Delta<>(0, 10, null, "A")), store.diff("p", 0, 1));
    }

    @Test
    void diffCoalescesAdjacentPiecesWithTheSameChange() {
        store.assertValue("p", 0, 10, "A");
        store.transact(t -> {
            t.assertValue("p", 0, 4, "B");
            t.assertValue("p", 4, 8, "B");
        });
        assertEquals(List.of(new Delta<>(0, 8, "A", "B")), store.diff("p", 1, 2));
    }

    @Test
    void diffKeepsAdjacentPiecesWithDifferentChangesApart() {
        store.assertValue("p", 0, 10, "A");
        store.transact(t -> {
            t.assertValue("p", 0, 4, "B");
            t.assertValue("p", 4, 8, "C");
        });
        assertEquals(List.of(new Delta<>(0, 4, "A", "B"), new Delta<>(4, 8, "A", "C")),
                store.diff("p", 1, 2));
    }

    @Test
    void revisionsTrackChangingBeliefAboutOneInstant() {
        now = 10;
        store.assertValue("p", 0, 10, "100");      // tx1: 5 -> 100
        now = 20;
        store.assertValue("p", 0, 3, "90");        // tx2: does not touch 5
        now = 30;
        store.assertValue("p", 4, 6, "120");       // tx3: 5 -> 120
        now = 40;
        store.assertValue("p", 0, 10, "120");      // tx4: 5 stays 120
        now = 50;
        store.retract("p", 5, 6);                  // tx5: 5 -> unknown
        now = 60;
        store.assertValue("p", 5, 6, "130");       // tx6: 5 -> 130
        assertEquals(List.of(new Revision<>(1, 10, "100"), new Revision<>(3, 30, "120"),
                new Revision<>(5, 50, null), new Revision<>(6, 60, "130")), store.revisions("p", 5));
    }

    @Test
    void revisionsOfNeverValuedInstantAreEmpty() {
        store.assertValue("p", 0, 10, "A");
        assertTrue(store.revisions("p", 20).isEmpty());
        assertTrue(store.revisions("zzz", 5).isEmpty());
    }

    @Test
    void revisionsStartAtFirstTxThatGaveTheInstantAValue() {
        store.assertValue("p", 0, 5, "A");
        store.assertValue("p", 5, 10, "B");
        assertEquals(List.of(new Revision<>(2, 100, "B")), store.revisions("p", 7));
    }

    @Test
    void argumentsAreValidated() {
        store.assertValue("p", 0, 10, "A");
        assertThrows(IllegalArgumentException.class, () -> store.valueAt("p", 1, -1));
        assertThrows(IllegalArgumentException.class, () -> store.valueAt("p", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> store.history("p", 2));
        assertThrows(IllegalArgumentException.class, () -> store.snapshot(1, 5));
        assertThrows(IllegalArgumentException.class, () -> store.diff("p", 0, 9));
        assertThrows(IllegalArgumentException.class, () -> store.assertValue(null, 0, 1, "A"));
        assertThrows(IllegalArgumentException.class, () -> store.assertValue("p", 0, 1, null));
        assertThrows(IllegalArgumentException.class, () -> store.assertValue("p", 4, 4, "A"));
        assertThrows(IllegalArgumentException.class, () -> store.retract("p", 4, 3));
        assertThrows(IllegalArgumentException.class, () -> store.retract(null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> store.transact(null));
        assertEquals(1, store.currentTx());
    }

    @Test
    void historyListsAreUnmodifiable() {
        store.assertValue("p", 0, 10, "A");
        assertThrows(UnsupportedOperationException.class, () -> store.history("p").clear());
        assertThrows(UnsupportedOperationException.class, () -> store.snapshot(1, 1).clear());
    }
}
