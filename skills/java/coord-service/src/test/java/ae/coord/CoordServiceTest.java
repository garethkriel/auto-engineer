package ae.coord;

import static org.junit.jupiter.api.Assertions.*;

import ae.coord.CoordException.Code;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoordServiceTest {
    ManualClock clock;
    CoordService svc;
    long s1;
    List<String> events;

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        svc = new CoordService(clock);
        s1 = svc.connect(1000);
        events = new ArrayList<>();
    }

    private java.util.function.Consumer<WatchEvent> rec(String tag) {
        return e -> events.add(tag + ":" + e.type() + ":" + e.path());
    }

    private static Code codeOf(org.junit.jupiter.api.function.Executable ex) {
        return assertThrows(CoordException.class, ex).code();
    }

    @Test
    void createReadUpdateDeleteRoundTrip() {
        assertEquals("/a", svc.create(s1, "/a", "x", CreateMode.PERSISTENT));
        CoordService.NodeData d = svc.getData(s1, "/a", null);
        assertEquals("x", d.data());
        assertEquals(0, d.stat().version());
        assertEquals(0L, d.stat().ephemeralOwner());
        Stat st = svc.setData(s1, "/a", "y", 0);
        assertEquals(1, st.version());
        assertEquals("y", svc.getData(s1, "/a", null).data());
        svc.delete(s1, "/a", 1);
        assertEquals(Optional.empty(), svc.exists(s1, "/a", null));
    }

    @Test
    void errorCodes() {
        assertEquals(Code.NO_NODE, codeOf(() -> svc.create(s1, "/a/b", "", CreateMode.PERSISTENT)));
        svc.create(s1, "/a", "", CreateMode.PERSISTENT);
        assertEquals(Code.NODE_EXISTS, codeOf(() -> svc.create(s1, "/a", "", CreateMode.PERSISTENT)));
        assertEquals(Code.NODE_EXISTS, codeOf(() -> svc.create(s1, "/", "", CreateMode.PERSISTENT)));
        assertEquals(Code.BAD_VERSION, codeOf(() -> svc.setData(s1, "/a", "z", 5)));
        assertEquals(Code.BAD_VERSION, codeOf(() -> svc.delete(s1, "/a", 3)));
        svc.create(s1, "/a/b", "", CreateMode.PERSISTENT);
        assertEquals(Code.NOT_EMPTY, codeOf(() -> svc.delete(s1, "/a", -1)));
        assertEquals(Code.NO_NODE, codeOf(() -> svc.delete(s1, "/zzz", -1)));
        assertEquals(Code.NO_NODE, codeOf(() -> svc.getData(s1, "/zzz", null)));
        assertEquals(Code.BAD_PATH, codeOf(() -> svc.delete(s1, "/", -1)));
        assertEquals(Code.NO_NODE, codeOf(() -> svc.setData(s1, "/zzz", "q", -1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "/a/", "//a", "/a//b", "/a/./b", "/a/..", "/a\u0000b"})
    void badPaths(String p) {
        assertEquals(Code.BAD_PATH, codeOf(() -> svc.create(s1, p, "", CreateMode.PERSISTENT)));
    }

    @Test
    void sequentialNamesUseParentCversionAndAreNeverReused() {
        svc.create(s1, "/q", "", CreateMode.PERSISTENT);
        String a = svc.create(s1, "/q/n-", "", CreateMode.PERSISTENT_SEQUENTIAL);
        String b = svc.create(s1, "/q/n-", "", CreateMode.PERSISTENT_SEQUENTIAL);
        assertEquals("/q/n-0000000000", a);
        assertEquals("/q/n-0000000001", b);
        svc.delete(s1, b, -1);
        // cversion is now 3 (2 creates + 1 delete): the deleted number 1 is never reused
        assertEquals("/q/n-0000000003", svc.create(s1, "/q/n-", "", CreateMode.PERSISTENT_SEQUENTIAL));
        assertEquals(List.of("n-0000000000", "n-0000000003"), svc.getChildren(s1, "/q", null));
    }

    @Test
    void childrenAreSortedAndStatCountsThem() {
        svc.create(s1, "/p", "", CreateMode.PERSISTENT);
        for (String n : List.of("c", "a", "b")) {
            svc.create(s1, "/p/" + n, "", CreateMode.PERSISTENT);
        }
        assertEquals(List.of("a", "b", "c"), svc.getChildren(s1, "/p", null));
        Stat st = svc.exists(s1, "/p", null).orElseThrow();
        assertEquals(3, st.numChildren());
        assertEquals(3, st.cversion());
    }

    @Test
    void zxidsAdvancePerCommittedTransactionOnly() {
        assertEquals(0, svc.lastZxid());
        svc.create(s1, "/a", "", CreateMode.PERSISTENT);
        assertEquals(1, svc.lastZxid());
        assertThrows(CoordException.class, () -> svc.create(s1, "/a", "", CreateMode.PERSISTENT));
        assertEquals(1, svc.lastZxid());
        Stat st = svc.setData(s1, "/a", "v", -1);
        assertEquals(2, st.mzxid());
        assertEquals(1, st.czxid());
        svc.getData(s1, "/a", null);
        assertEquals(2, svc.lastZxid());
    }

    @Test
    void ephemeralNodesBelongToSessionAndCannotHaveChildren() {
        svc.create(s1, "/e", "", CreateMode.EPHEMERAL);
        assertEquals(s1, svc.exists(s1, "/e", null).orElseThrow().ephemeralOwner());
        assertEquals(Code.EPHEMERAL_PARENT,
                codeOf(() -> svc.create(s1, "/e/c", "", CreateMode.PERSISTENT)));
    }

    @Test
    void sessionExpiryHappensExactlyAtTimeoutAndRemovesEphemerals() {
        long s2 = svc.connect(500);
        svc.create(s2, "/e", "", CreateMode.EPHEMERAL);
        clock.advance(499);
        assertTrue(svc.exists(s1, "/e", null).isPresent());
        clock.advance(1);
        assertTrue(svc.exists(s1, "/e", null).isEmpty());
        assertEquals(Code.SESSION_EXPIRED, codeOf(() -> svc.create(s2, "/x", "", CreateMode.PERSISTENT)));
    }

    @Test
    void heartbeatExtendsTheDeadlineFromNow() {
        long s2 = svc.connect(500);
        svc.create(s2, "/e", "", CreateMode.EPHEMERAL);
        clock.advance(400);
        svc.heartbeat(s2);
        clock.advance(400);
        assertTrue(svc.exists(s1, "/e", null).isPresent(), "alive 400 after heartbeat");
        clock.advance(100);
        assertTrue(svc.exists(s1, "/e", null).isEmpty());
    }

    @Test
    void expiredAndUnknownSessionsAreRejected() {
        assertEquals(Code.SESSION_EXPIRED, codeOf(() -> svc.heartbeat(999)));
        svc.close(s1);
        assertEquals(Code.SESSION_EXPIRED, codeOf(() -> svc.getChildren(s1, "/", null)));
        assertThrows(IllegalArgumentException.class, () -> svc.connect(0));
    }

    @Test
    void sessionIdsAreSequentialAndExpiryTransactionConsumesOneZxid() {
        long s2 = svc.connect(10);
        assertEquals(s1 + 1, s2);
        svc.create(s2, "/a", "", CreateMode.EPHEMERAL);
        svc.create(s2, "/b", "", CreateMode.EPHEMERAL);
        long before = svc.lastZxid();
        svc.close(s2);
        assertEquals(before + 1, svc.lastZxid());
        svc.close(svc.connect(10)); // nothing to remove: no zxid
        assertEquals(before + 1, svc.lastZxid());
    }

    @Test
    void dataWatchFiresOnceOnChangeThenOnDelete() {
        svc.create(s1, "/a", "", CreateMode.PERSISTENT);
        svc.getData(s1, "/a", rec("w"));
        svc.setData(s1, "/a", "1", -1);
        svc.setData(s1, "/a", "2", -1); // watch already consumed
        assertEquals(List.of("w:NODE_DATA_CHANGED:/a"), events);
        svc.getData(s1, "/a", rec("w2"));
        svc.delete(s1, "/a", -1);
        assertEquals(List.of("w:NODE_DATA_CHANGED:/a", "w2:NODE_DELETED:/a"), events);
    }

    @Test
    void existsWatchOnMissingNodeFiresOnCreate() {
        assertTrue(svc.exists(s1, "/n", rec("x")).isEmpty());
        svc.create(s1, "/n", "", CreateMode.PERSISTENT);
        assertEquals(List.of("x:NODE_CREATED:/n"), events);
    }

    @Test
    void childWatchFiresOnChildCreateAndDeleteButNotOnDataChange() {
        svc.create(s1, "/p", "", CreateMode.PERSISTENT);
        svc.getChildren(s1, "/p", rec("c"));
        svc.setData(s1, "/p", "d", -1);
        assertEquals(List.of(), events);
        svc.create(s1, "/p/k", "", CreateMode.PERSISTENT);
        assertEquals(List.of("c:NODE_CHILDREN_CHANGED:/p"), events);
        svc.getChildren(s1, "/p", rec("c2"));
        svc.delete(s1, "/p/k", -1);
        assertEquals(List.of("c:NODE_CHILDREN_CHANGED:/p", "c2:NODE_CHILDREN_CHANGED:/p"), events);
    }

    @Test
    void deletingAWatchedNodeNotifiesDataWatchChildWatchAndParentChildWatchInOrder() {
        svc.create(s1, "/p", "", CreateMode.PERSISTENT);
        svc.create(s1, "/p/k", "", CreateMode.PERSISTENT);
        svc.getChildren(s1, "/p", rec("parent"));
        svc.getChildren(s1, "/p/k", rec("kids"));
        svc.getData(s1, "/p/k", rec("data"));
        svc.delete(s1, "/p/k", -1);
        assertEquals(List.of("data:NODE_DELETED:/p/k", "kids:NODE_DELETED:/p/k",
                "parent:NODE_CHILDREN_CHANGED:/p"), events);
    }

    @Test
    void watchersRunOutsideTheLockAndMayReenter() {
        svc.create(s1, "/a", "", CreateMode.PERSISTENT);
        Thread[] seen = new Thread[1];
        svc.getData(s1, "/a", e -> {
            seen[0] = Thread.currentThread();
            // re-arm from inside the callback and read back
            svc.getData(s1, "/a", rec("again"));
            svc.create(s1, "/b", "from-watcher", CreateMode.PERSISTENT);
        });
        svc.setData(s1, "/a", "1", -1);
        assertEquals("from-watcher", svc.getData(s1, "/b", null).data());
        svc.setData(s1, "/a", "2", -1);
        assertEquals(List.of("again:NODE_DATA_CHANGED:/a"), events);
    }

    @Test
    void anotherThreadCanUseTheServiceWhileAWatcherBlocks() throws Exception {
        svc.create(s1, "/a", "", CreateMode.PERSISTENT);
        java.util.concurrent.CountDownLatch inWatcher = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        svc.getData(s1, "/a", e -> {
            inWatcher.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        Thread t = new Thread(() -> svc.setData(s1, "/a", "1", -1));
        t.start();
        assertTrue(inWatcher.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // watcher is blocked; the service must still answer from this thread
        assertEquals("1", svc.getData(s1, "/a", null).data());
        release.countDown();
        t.join(5000);
        assertFalse(t.isAlive());
    }

    @Test
    void expiryFiresDeleteAndChildWatchesAndDropsTheExpiredSessionsOwnWatches() {
        long s2 = svc.connect(100);
        svc.create(s1, "/lock", "", CreateMode.PERSISTENT);
        svc.create(s2, "/lock/e", "", CreateMode.EPHEMERAL);
        svc.getChildren(s1, "/lock", rec("alive"));
        svc.getData(s1, "/lock/e", rec("alive-data"));
        svc.getData(s2, "/lock", rec("dead"));
        clock.advance(100);
        svc.tick();
        assertEquals(List.of("alive-data:NODE_DELETED:/lock/e", "alive:NODE_CHILDREN_CHANGED:/lock"), events);
    }

    @Test
    void closeDropsWatchesOfThatSession() {
        long s2 = svc.connect(1000);
        svc.create(s1, "/a", "", CreateMode.PERSISTENT);
        svc.getData(s2, "/a", rec("gone"));
        svc.close(s2);
        svc.setData(s1, "/a", "1", -1);
        assertEquals(List.of(), events);
    }

    @Test
    void multiAppliesAllOps() {
        List<String> res = svc.multi(s1, List.of(
                new Op.Create("/a", "1", CreateMode.PERSISTENT),
                new Op.Create("/a/s-", "2", CreateMode.PERSISTENT_SEQUENTIAL),
                new Op.SetData("/a", "3", 0),
                new Op.Check("/a", 1)));
        assertEquals(List.of("/a", "/a/s-0000000000", "/a", "/a"), res);
        assertEquals("3", svc.getData(s1, "/a", null).data());
        assertEquals(1, svc.lastZxid());
    }

    @Test
    void multiFailureRollsBackEverythingIncludingWatchesAndSequenceCounters() {
        svc.create(s1, "/q", "", CreateMode.PERSISTENT);
        svc.getChildren(s1, "/q", rec("w"));
        long z = svc.lastZxid();
        CoordException ex = assertThrows(CoordException.class, () -> svc.multi(s1, List.of(
                new Op.Create("/q/n-", "", CreateMode.PERSISTENT_SEQUENTIAL),
                new Op.Create("/q/n-", "", CreateMode.PERSISTENT_SEQUENTIAL),
                new Op.Delete("/missing", -1),
                new Op.Create("/q/z", "", CreateMode.PERSISTENT))));
        assertEquals(Code.NO_NODE, ex.code());
        assertEquals(2, ex.opIndex());
        assertEquals(z, svc.lastZxid());
        assertEquals(List.of(), svc.getChildren(s1, "/q", null));
        assertEquals(List.of(), events);
        assertEquals("/q/n-0000000000", svc.create(s1, "/q/n-", "", CreateMode.PERSISTENT_SEQUENTIAL));
    }

    @Test
    void multiCheckFailureReportsBadVersionAtItsIndex() {
        svc.create(s1, "/a", "", CreateMode.PERSISTENT);
        CoordException ex = assertThrows(CoordException.class, () -> svc.multi(s1, List.of(
                new Op.SetData("/a", "x", -1), new Op.Check("/a", 0))));
        assertEquals(Code.BAD_VERSION, ex.code());
        assertEquals(1, ex.opIndex());
        assertEquals("", svc.getData(s1, "/a", null).data());
    }

    @Test
    void multiSeesItsOwnEarlierEffectsAndNotifiesEachWatcherOnce() {
        svc.create(s1, "/p", "", CreateMode.PERSISTENT);
        svc.getChildren(s1, "/p", rec("c"));
        svc.multi(s1, List.of(
                new Op.Create("/p/a", "", CreateMode.PERSISTENT),
                new Op.Create("/p/a/b", "", CreateMode.PERSISTENT),
                new Op.Delete("/p/a/b", -1),
                new Op.Delete("/p/a", -1)));
        assertEquals(List.of("c:NODE_CHILDREN_CHANGED:/p"), events);
        assertEquals(List.of(), svc.getChildren(s1, "/p", null));
    }

    @Test
    void emptyMultiIsANoOp() {
        assertEquals(List.of(), svc.multi(s1, List.of()));
        assertEquals(0, svc.lastZxid());
    }

    @Test
    void invariantsHoldAfterMixedUse() {
        long s2 = svc.connect(50);
        svc.create(s1, "/a", "", CreateMode.PERSISTENT);
        svc.create(s2, "/a/e", "", CreateMode.EPHEMERAL);
        svc.checkInvariants();
        clock.advance(60);
        svc.tick();
        svc.checkInvariants();
        assertEquals(List.of(), svc.getChildren(s1, "/a", null));
    }
}
