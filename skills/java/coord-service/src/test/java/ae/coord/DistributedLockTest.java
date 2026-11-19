package ae.coord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DistributedLockTest {
    ManualClock clock;
    CoordService svc;
    DistributedLock lock;
    List<String> log;

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        svc = new CoordService(clock);
        lock = new DistributedLock(svc, "/locks/db");
        log = new ArrayList<>();
    }

    private DistributedLock.Handle req(long session, String name) {
        return lock.request(session, token -> log.add(name + "=" + token));
    }

    @Test
    void firstRequesterIsGrantedImmediatelyWithTheFirstToken() {
        long a = svc.connect(1000);
        DistributedLock.Handle h = req(a, "A");
        assertEquals(DistributedLock.State.GRANTED, h.state());
        assertEquals(List.of("A=0"), log);
        assertEquals(0L, h.token());
    }

    @Test
    void waitersAreGrantedInRequestOrderAndEachExactlyOnce() {
        long a = svc.connect(1000), b = svc.connect(1000), c = svc.connect(1000);
        DistributedLock.Handle ha = req(a, "A");
        DistributedLock.Handle hb = req(b, "B");
        DistributedLock.Handle hc = req(c, "C");
        assertEquals(DistributedLock.State.WAITING, hb.state());
        assertEquals(List.of("A=0"), log);
        ha.release();
        assertEquals(List.of("A=0", "B=1"), log);
        assertEquals(DistributedLock.State.RELEASED, ha.state());
        assertEquals(DistributedLock.State.WAITING, hc.state());
        hb.release();
        assertEquals(List.of("A=0", "B=1", "C=2"), log);
        hc.release();
        assertEquals(List.of("A=0", "B=1", "C=2"), log);
    }

    @Test
    void releaseIsIdempotent() {
        long a = svc.connect(1000);
        DistributedLock.Handle ha = req(a, "A");
        ha.release();
        ha.release();
        assertEquals(DistributedLock.State.RELEASED, ha.state());
    }

    @Test
    void releasingAWaitingHandleRemovesItFromTheQueueWithoutGrantingIt() {
        long a = svc.connect(1000), b = svc.connect(1000), c = svc.connect(1000);
        DistributedLock.Handle ha = req(a, "A");
        DistributedLock.Handle hb = req(b, "B");
        req(c, "C");
        hb.release();
        assertEquals(List.of("A=0"), log);
        ha.release();
        assertEquals(List.of("A=0", "C=2"), log, "C inherits after A, B never granted");
    }

    @Test
    void expiredHolderHandsOverAndTheHolderHandleIsLost() {
        long a = svc.connect(100), b = svc.connect(1000);
        DistributedLock.Handle ha = req(a, "A");
        req(b, "B");
        clock.advance(100);
        svc.heartbeat(b); // triggers expiry processing of A
        assertEquals(List.of("A=0", "B=1"), log);
        assertEquals(DistributedLock.State.LOST, ha.state());
    }

    @Test
    void middleWaiterDyingDoesNotGrantTheLastWaiterEarly() {
        long a = svc.connect(1000), b = svc.connect(100), c = svc.connect(1000);
        DistributedLock.Handle ha = req(a, "A");
        DistributedLock.Handle hb = req(b, "B");
        DistributedLock.Handle hc = req(c, "C");
        clock.advance(100);
        svc.heartbeat(a);
        svc.heartbeat(c); // keep a and c alive past b's expiry
        assertEquals(DistributedLock.State.LOST, hb.state());
        assertEquals(DistributedLock.State.WAITING, hc.state());
        assertEquals(List.of("A=0"), log);
        ha.release();
        assertEquals(List.of("A=0", "C=2"), log);
    }

    @Test
    void tokensAreStrictlyIncreasingAcrossHoldersAndNeverReused() {
        long a = svc.connect(1000);
        List<Long> tokens = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            DistributedLock.Handle h = lock.request(a, tokens::add);
            h.release();
        }
        assertEquals(List.of(0L, 2L, 4L, 6L, 8L), tokens,
                "each request+release bumps the parent cversion twice");
    }

    @Test
    void fencingRejectsAStaleHolderThatWokeUpLate() {
        long a = svc.connect(100), b = svc.connect(1000);
        FencedResource disk = new FencedResource();
        DistributedLock.Handle ha = req(a, "A");
        assertTrue(disk.write(ha.token(), "from A"));
        DistributedLock.Handle hb = req(b, "B");
        clock.advance(100); // A is partitioned: its session times out
        svc.heartbeat(b);
        assertEquals(DistributedLock.State.GRANTED, hb.state());
        assertTrue(disk.write(hb.token(), "from B"));
        assertFalse(disk.write(ha.token(), "late write from A"), "stale token must be fenced");
        assertEquals("from B", disk.content());
    }

    @Test
    void requestWithDeadSessionFails() {
        long a = svc.connect(10);
        svc.close(a);
        assertEquals(CoordException.Code.SESSION_EXPIRED,
                assertThrows(CoordException.class, () -> req(a, "A")).code());
    }

    @Test
    void foreignChildrenUnderTheLockPathAreIgnored() {
        long a = svc.connect(1000);
        svc.create(a, "/locks/db/aaa-junk", "", CreateMode.PERSISTENT);
        DistributedLock.Handle h = req(a, "A");
        assertEquals(DistributedLock.State.GRANTED, h.state());
    }

    /** A resource that remembers the highest token it has seen. */
    static final class FencedResource {
        private long highest = -1;
        private String content = "";

        synchronized boolean write(long token, String data) {
            if (token < highest) {
                return false;
            }
            highest = token;
            content = data;
            return true;
        }

        synchronized String content() {
            return content;
        }
    }
}
