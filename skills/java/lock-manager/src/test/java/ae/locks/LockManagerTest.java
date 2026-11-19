package ae.locks;

import static ae.locks.LockMode.IS;
import static ae.locks.LockMode.IX;
import static ae.locks.LockMode.S;
import static ae.locks.LockMode.SIX;
import static ae.locks.LockMode.X;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.locks.LockManager.Events;
import ae.locks.LockManager.Outcome;
import ae.locks.LockManager.Status;
import ae.locks.LockManager.TxnState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LockManagerTest {
    private LockManager m;

    @BeforeEach
    void setUp() {
        m = new LockManager();
    }

    private void begin(long... ids) {
        for (long id : ids) {
            m.begin(id);
        }
    }

    private Outcome acq(long t, String r, LockMode mode) {
        Outcome o = m.acquire(t, r, mode);
        m.checkInvariants();
        return o;
    }

    private Events commit(long t) {
        Events e = m.commit(t);
        m.checkInvariants();
        return e;
    }

    private static Map<String, LockMode> locks(Object... kv) {
        Map<String, LockMode> map = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], (LockMode) kv[i + 1]);
        }
        return map;
    }

    // ---- basic compatibility and FIFO -------------------------------------------------

    @Test
    void readersShareWriterWaitsUntilAllReadersLeave() {
        begin(1, 2, 3);
        assertEquals(Status.GRANTED, acq(1, "a", S).status());
        assertEquals(Status.GRANTED, acq(2, "a", S).status());
        assertEquals(Status.WAITING, acq(3, "a", X).status());
        assertEquals(TxnState.BLOCKED, m.state(3));
        assertEquals(List.of(3L), m.queue("a"));

        assertEquals(List.of(), commit(1).unblocked());
        assertEquals(TxnState.BLOCKED, m.state(3));
        assertEquals(List.of(3L), commit(2).unblocked());
        assertEquals(TxnState.ACTIVE, m.state(3));
        assertEquals(Optional.of(X), m.held(3, "a"));
    }

    @Test
    void queueIsStrictFifoSoALaterCompatibleReaderDoesNotOvertake() {
        begin(1, 2, 3);
        acq(1, "a", S);
        assertEquals(Status.WAITING, acq(2, "a", X).status());
        // S is compatible with holder 1 but must queue behind the waiting writer
        assertEquals(Status.WAITING, acq(3, "a", S).status());
        assertEquals(List.of(2L, 3L), m.queue("a"));

        assertEquals(List.of(2L), commit(1).unblocked());
        assertEquals(TxnState.BLOCKED, m.state(3));
        assertEquals(List.of(3L), commit(2).unblocked());
    }

    @Test
    void releaseWakesAllCompatibleWaitersAtTheHeadInOrder() {
        begin(1, 2, 3, 4, 5);
        acq(1, "a", X);
        acq(2, "a", S);
        acq(3, "a", S);
        acq(4, "a", X);
        acq(5, "a", S);
        assertEquals(List.of(2L, 3L), commit(1).unblocked());
        assertEquals(List.of(4L, 5L), m.queue("a"), "4 is blocked by 2 and 3 holding S; 5 waits behind 4");
        assertEquals(List.of(), commit(2).unblocked());
        assertEquals(List.of(4L), commit(3).unblocked());
        assertEquals(List.of(5L), commit(4).unblocked());
    }

    // ---- hierarchy --------------------------------------------------------------------

    @Test
    void acquiringARowTakesIntentionLocksOnAncestors() {
        begin(1);
        acq(1, "db/t/r1", X);
        assertEquals(locks("db", IX, "db/t", IX, "db/t/r1", X), m.locks(1));
        acq(1, "db/t/r2", S);
        assertEquals(locks("db", IX, "db/t", IX, "db/t/r1", X, "db/t/r2", S), m.locks(1));
    }

    @Test
    void readIntentionAncestorsAreIS() {
        begin(1);
        acq(1, "db/t/r1", S);
        assertEquals(locks("db", IS, "db/t", IS, "db/t/r1", S), m.locks(1));
    }

    @Test
    void differentRowsDoNotConflictButATableScanDoes() {
        begin(1, 2, 3);
        assertEquals(Status.GRANTED, acq(1, "db/t/r1", X).status());
        assertEquals(Status.GRANTED, acq(2, "db/t/r2", X).status());
        // a table-level S lock conflicts with the IX intentions on the table
        assertEquals(Status.WAITING, acq(3, "db/t", S).status());
        assertEquals(List.of(), commit(1).unblocked());
        assertEquals(List.of(3L), commit(2).unblocked());
    }

    @Test
    void tableLockCoversRowsOfTheSameTransaction() {
        begin(1);
        acq(1, "db/t", X);
        acq(1, "db/t/r9", S);
        acq(1, "db/t/r9", X);
        // X on the table already covers the intention locks; the row gets its own entry
        assertEquals(locks("db", IX, "db/t", X, "db/t/r9", X), m.locks(1));
    }

    @Test
    void scanThenUpdateUpgradesTheTableToSix() {
        begin(1, 2, 3);
        acq(1, "db/t", S);
        acq(1, "db/t/r1", X);
        assertEquals(locks("db", IX, "db/t", SIX, "db/t/r1", X), m.locks(1));
        // SIX admits intention-share readers only
        assertEquals(Status.GRANTED, acq(2, "db/t/r5", S).status());
        assertEquals(Status.WAITING, acq(3, "db/t/r6", X).status());
    }

    @Test
    void resumedPlanContinuesAfterAnAncestorStepIsGranted() {
        begin(1, 2);
        acq(1, "a/b", S);
        // 2 gets IX on a, then must wait for IX on a/b (blocked by S held by 1)
        assertEquals(Status.WAITING, acq(2, "a/b/c", X).status());
        assertEquals(locks("a", IX), m.locks(2));
        assertEquals(List.of(2L), m.queue("a/b"));
        assertEquals(List.of(2L), commit(1).unblocked());
        assertEquals(locks("a", IX, "a/b", IX, "a/b/c", X), m.locks(2));
    }

    @Test
    void resumedPlanThatBlocksAgainIsNotReportedUntilItFinishes() {
        begin(1, 2, 3);
        acq(1, "a/b", S);
        acq(3, "a/b/c", S);
        assertEquals(Status.WAITING, acq(2, "a/b/c", X).status());
        assertEquals(List.of(2L), m.queue("a/b"));
        Events e = commit(1);
        assertEquals(List.of(), e.unblocked(), "woken at a/b but now blocked by 3 on the row");
        assertEquals(TxnState.BLOCKED, m.state(2));
        assertEquals(List.of(2L), m.queue("a/b/c"));
        assertEquals(List.of(2L), commit(3).unblocked());
        assertEquals(locks("a", IX, "a/b", IX, "a/b/c", X), m.locks(2));
    }

    // ---- upgrades ---------------------------------------------------------------------

    @Test
    void upgradeIsGrantedAtOnceWhenNoOtherHolder() {
        begin(1, 2);
        acq(1, "a", S);
        assertEquals(Status.WAITING, acq(2, "a", X).status());
        // the upgrade ignores the waiting writer in the queue
        assertEquals(Status.GRANTED, acq(1, "a", X).status());
        assertEquals(Optional.of(X), m.held(1, "a"));
        assertEquals(List.of(2L), m.queue("a"));
    }

    @Test
    void waitingUpgradeIsQueuedAheadOfFreshRequests() {
        begin(1, 2, 3);
        acq(1, "a", S);
        acq(3, "a", S);
        assertEquals(Status.WAITING, acq(2, "a", X).status());
        assertEquals(Status.WAITING, acq(1, "a", X).status());
        assertEquals(List.of(1L, 2L), m.queue("a"));
        assertEquals(List.of(1L), commit(3).unblocked());
        assertEquals(Optional.of(X), m.held(1, "a"));
        assertEquals(List.of(2L), m.queue("a"));
    }

    @Test
    void severalWaitingUpgradesKeepArrivalOrderAheadOfFreshRequests() {
        begin(1, 2, 3, 4);
        acq(1, "a", IS);
        acq(2, "a", IS);
        acq(3, "a", S);
        assertEquals(Status.WAITING, acq(4, "a", X).status());
        // IS -> IX conflicts with the S holder only, so these two upgrades do not deadlock
        assertEquals(Status.WAITING, acq(1, "a", IX).status());
        assertEquals(Status.WAITING, acq(2, "a", IX).status());
        assertEquals(List.of(1L, 2L, 4L), m.queue("a"));
        assertEquals(List.of(1L, 2L), commit(3).unblocked());
        assertEquals(Optional.of(IX), m.held(1, "a"));
        assertEquals(Optional.of(IX), m.held(2, "a"));
        assertEquals(List.of(4L), m.queue("a"));
    }

    @Test
    void requestAlreadyCoveredIsANoOp() {
        begin(1);
        acq(1, "a", X);
        assertEquals(Status.GRANTED, acq(1, "a", S).status());
        assertEquals(Optional.of(X), m.held(1, "a"));
        assertEquals(Optional.empty(), m.held(1, "zzz"));
    }

    // ---- deadlocks --------------------------------------------------------------------

    @Test
    void twoReadersUpgradingDeadlockAndTheYoungestDies() {
        begin(1, 2);
        acq(1, "a", S);
        acq(2, "a", S);
        assertEquals(Status.WAITING, acq(1, "a", X).status());
        Outcome o = acq(2, "a", X);
        assertEquals(Status.ABORTED, o.status());
        assertEquals(List.of(2L), o.events().aborted());
        assertEquals(List.of(1L), o.events().unblocked());
        assertEquals(TxnState.ABORTED, m.state(2));
        assertEquals(Optional.of(X), m.held(1, "a"));
        assertEquals(Map.of(), m.locks(2));
    }

    @Test
    void victimIsTheYoungestEvenWhenItIsNotTheRequester() {
        begin(1, 2); // age: 1 older than 2
        acq(2, "b", X);
        acq(1, "a", X);
        assertEquals(Status.WAITING, acq(2, "a", X).status());
        Outcome o = acq(1, "b", X); // closes the cycle; youngest is txn 2
        assertEquals(Status.GRANTED, o.status());
        assertEquals(List.of(2L), o.events().aborted());
        assertEquals(List.of(), o.events().unblocked());
        assertEquals(TxnState.ABORTED, m.state(2));
        assertEquals(locks("a", X, "b", X), m.locks(1));
    }

    @Test
    void ageIsBeginOrderNotIdOrder() {
        begin(9, 3); // 9 is older although its id is larger
        acq(9, "a", X);
        acq(3, "b", X);
        assertEquals(Status.WAITING, acq(9, "b", X).status());
        Outcome o = acq(3, "a", X);
        assertEquals(Status.ABORTED, o.status(), "3 began last so it is the victim");
        assertEquals(List.of(9L), o.events().unblocked());
    }

    @Test
    void threePartyCycleAbortsOnlyTheYoungest() {
        begin(1, 2, 3);
        acq(1, "a", X);
        acq(2, "b", X);
        acq(3, "c", X);
        assertEquals(Status.WAITING, acq(1, "b", X).status());
        assertEquals(Status.WAITING, acq(2, "c", X).status());
        Outcome o = acq(3, "a", X);
        assertEquals(Status.ABORTED, o.status());
        assertEquals(List.of(3L), o.events().aborted());
        assertEquals(List.of(2L), o.events().unblocked());
        assertEquals(TxnState.BLOCKED, m.state(1));
        assertEquals(List.of(1L), commit(2).unblocked());
    }

    @Test
    void cycleThatNeedsAFifoEdgeIsDetected() {
        begin(1, 2, 3);
        acq(3, "b", X);
        acq(1, "a", S);
        assertEquals(Status.WAITING, acq(2, "a", X).status()); // 2 -> 1 (holder)
        // 3 asks for S on a: compatible with holder 1, so its only wait-for edge is 3 -> 2 (queued writer)
        assertEquals(Status.WAITING, acq(3, "a", S).status());
        // 1 asks for b held by 3: edges 1 -> 3, 3 -> 2 (FIFO only), 2 -> 1 form a cycle; youngest is 3
        Outcome o = acq(1, "b", X);
        assertEquals(Status.GRANTED, o.status());
        assertEquals(List.of(3L), o.events().aborted());
        assertEquals(List.of(), o.events().unblocked());
        assertEquals(locks("a", S, "b", X), m.locks(1));
        assertEquals(List.of(2L), m.queue("a"));
    }

    @Test
    void waiterBehindAVictimIsGrantedOnceTheVictimIsGone() {
        begin(1, 2, 3);
        acq(2, "b", X);
        acq(1, "a", S);
        assertEquals(Status.WAITING, acq(2, "a", X).status()); // 2 waits for 1
        assertEquals(Status.WAITING, acq(3, "a", S).status()); // queued behind 2
        Outcome o = acq(1, "b", S); // cycle {1,2}; youngest is 2
        assertEquals(Status.GRANTED, o.status());
        assertEquals(List.of(2L), o.events().aborted());
        assertEquals(List.of(3L), o.events().unblocked());
        assertEquals(Optional.of(S), m.held(3, "a"));
    }

    @Test
    void victimBlockedInAQueueBehindOthersIsRemovedAndTheOtherSideProceeds() {
        begin(1, 2, 3);
        acq(1, "a/b", S);
        acq(2, "a/b/c", X); // waits at a/b
        assertEquals(TxnState.BLOCKED, m.state(2));
        acq(3, "z", X);
        assertEquals(Status.WAITING, acq(1, "z", S).status());
        // 3 requests X on a/b: waits for holder 1 and (FIFO) for queued 2; 1 waits for 3 -> cycle {1,3}
        Outcome o = acq(3, "a/b", X);
        assertEquals(Status.ABORTED, o.status());
        assertEquals(List.of(3L), o.events().aborted());
        assertEquals(List.of(1L), o.events().unblocked());
    }

    // ---- release, abort, 2PL ----------------------------------------------------------

    @Test
    void abortWhileBlockedRemovesTheQueueEntryAndLetsTheNextWaiterThrough() {
        begin(1, 2, 3);
        acq(1, "a", S);
        acq(2, "a", X);
        acq(3, "a", S);
        Events e = m.abort(2);
        m.checkInvariants();
        assertEquals(List.of(3L), e.unblocked());
        assertEquals(TxnState.ABORTED, m.state(2));
        assertEquals(Map.of(), m.locks(2));
    }

    @Test
    void unlockReleasesOneLockWakesWaitersAndStartsShrinking() {
        begin(1, 2);
        acq(1, "a/b", X);
        assertEquals(Status.WAITING, acq(2, "a/b", S).status());
        assertThrows(IllegalStateException.class, () -> m.unlock(1, "a"), "a/b is still held below a");
        Events e = m.unlock(1, "a/b");
        m.checkInvariants();
        assertEquals(List.of(2L), e.unblocked());
        assertEquals(locks("a", IX), m.locks(1));
        assertThrows(IllegalStateException.class, () -> m.acquire(1, "q", S));
        assertEquals(TxnState.ACTIVE, m.state(1));
        m.unlock(1, "a");
        commit(1);
    }

    @Test
    void unlockOfAnUnheldResourceIsRejected() {
        begin(1);
        acq(1, "a", S);
        assertThrows(IllegalArgumentException.class, () -> m.unlock(1, "b"));
        assertEquals(locks("a", S), m.locks(1));
    }

    // ---- validation and no side effects -----------------------------------------------

    @Test
    void invalidCallsThrowAndLeaveEverythingUnchanged() {
        begin(1, 2);
        acq(1, "a/b", S);
        acq(2, "a/b", X);
        Map<String, LockMode> before1 = m.locks(1);
        assertThrows(IllegalArgumentException.class, () -> m.begin(1));
        assertThrows(IllegalArgumentException.class, () -> m.acquire(99, "a", S));
        for (String bad : new String[] {null, "", "/a", "a/", "a//b", "/"}) {
            assertThrows(IllegalArgumentException.class, () -> m.acquire(1, bad, S), String.valueOf(bad));
        }
        assertThrows(NullPointerException.class, () -> m.acquire(1, "a", null));
        assertThrows(IllegalStateException.class, () -> m.acquire(2, "c", S), "2 is blocked");
        assertThrows(IllegalStateException.class, () -> m.commit(2), "2 is blocked");
        assertThrows(IllegalStateException.class, () -> m.unlock(2, "a"), "2 is blocked");
        assertEquals(before1, m.locks(1));
        assertEquals(List.of(2L), m.queue("a/b"));
        assertEquals(locks("a", IX), m.locks(2));
        m.checkInvariants();
    }

    @Test
    void endedTransactionsCannotBeReusedOrFinishedTwice() {
        begin(1);
        acq(1, "a", S);
        commit(1);
        assertEquals(TxnState.COMMITTED, m.state(1));
        assertThrows(IllegalStateException.class, () -> m.acquire(1, "a", S));
        assertThrows(IllegalStateException.class, () -> m.commit(1));
        assertThrows(IllegalStateException.class, () -> m.abort(1));
        assertThrows(IllegalArgumentException.class, () -> m.begin(1));
        assertEquals(Map.of(), m.holders("a"));
        assertEquals(List.of(), m.queue("a"));
    }

    @Test
    void holdersViewIsASortedCopy() {
        begin(5, 2);
        acq(5, "a", IS);
        acq(2, "a", IX);
        Map<Long, LockMode> h = m.holders("a");
        assertEquals(List.of(2L, 5L), List.copyOf(h.keySet()));
        h.clear();
        assertEquals(2, m.holders("a").size());
        assertTrue(m.holders("nothing").isEmpty());
    }
}
