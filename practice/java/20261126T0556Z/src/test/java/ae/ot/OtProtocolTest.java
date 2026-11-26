package ae.ot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class OtProtocolTest {

    private static TextOp ins(int len, int pos, String s) {
        return TextOp.builder().retain(pos).insert(s).retain(len - pos).build();
    }

    private static TextOp del(int len, int from, int to) {
        return TextOp.builder().retain(from).delete(to - from).retain(len - to).build();
    }

    // ---- server ----

    @Test
    void serverAppliesInOrderWhenUpToDate() {
        OtServer s = new OtServer("abc");
        OtServer.Result r1 = s.submit(0, ins(3, 3, "d"));
        assertEquals(1, r1.revision());
        assertEquals("abcd", s.document());
        OtServer.Result r2 = s.submit(1, del(4, 0, 1));
        assertEquals(2, r2.revision());
        assertEquals("bcd", s.document());
        assertEquals(2, s.revision());
    }

    @Test
    void serverTransformsStaleOpAndCommittedOpsWinTies() {
        OtServer s = new OtServer("abc");
        s.submit(0, ins(3, 1, "X"));                         // "aXbc"
        OtServer.Result r = s.submit(0, ins(3, 1, "Y"));      // concurrent insert at the same place
        assertEquals("aXYbc", s.document());                  // server op first
        assertEquals(2, r.revision());
        assertEquals(ins(4, 2, "Y"), r.op());
    }

    @Test
    void serverTransformsAgainstSeveralOps() {
        OtServer s = new OtServer("abcdef");
        s.submit(0, del(6, 0, 2));       // "cdef"
        s.submit(1, ins(4, 4, "!"));     // "cdef!"
        OtServer.Result r = s.submit(0, del(6, 1, 5));  // delete "bcde" from original
        assertEquals("f!", s.document());
        assertEquals(3, r.revision());
    }

    @Test
    void serverRejectsBadSubmissionsWithoutChange() {
        OtServer s = new OtServer("abc");
        s.submit(0, ins(3, 0, "Z"));
        assertThrows(IllegalArgumentException.class, () -> s.submit(-1, TextOp.identity(3)));
        assertThrows(IllegalArgumentException.class, () -> s.submit(2, TextOp.identity(4)));
        assertThrows(IllegalArgumentException.class, () -> s.submit(1, TextOp.identity(3)));  // wrong length at rev 1
        assertThrows(IllegalArgumentException.class, () -> s.submit(0, TextOp.identity(4)));  // wrong length at rev 0
        assertEquals("Zabc", s.document());
        assertEquals(1, s.revision());
        assertEquals(1, s.history().size());
    }

    @Test
    void serverReplayOfHistoryEqualsDocument() {
        OtServer s = new OtServer("hello");
        s.submit(0, ins(5, 5, " world"));
        s.submit(1, del(11, 0, 1));
        s.submit(0, ins(5, 0, ">"));
        String replay = "hello";
        for (TextOp o : s.history()) replay = o.apply(replay);
        assertEquals(s.document(), replay);
    }

    // ---- client state machine ----

    @Test
    void clientSendsFirstEditAndBuffersTheRest() {
        OtClient c = new OtClient("abc");
        Optional<OtClient.Message> m1 = c.localEdit(ins(3, 3, "d"));
        assertTrue(m1.isPresent());
        assertEquals(0, m1.get().baseRevision());
        assertEquals(OtClient.State.AWAITING_ACK, c.state());
        assertTrue(c.localEdit(ins(4, 4, "e")).isEmpty());
        assertEquals(OtClient.State.AWAITING_WITH_BUFFER, c.state());
        assertTrue(c.localEdit(del(5, 0, 1)).isEmpty());
        assertEquals("bcde", c.document());
        // ack releases exactly the composed buffer, based on the acked revision
        Optional<OtClient.Message> m2 = c.serverAck(1);
        assertTrue(m2.isPresent());
        assertEquals(1, m2.get().baseRevision());
        assertEquals(ins(4, 4, "e").compose(del(5, 0, 1)), m2.get().op());
        assertEquals(OtClient.State.AWAITING_ACK, c.state());
        assertTrue(c.serverAck(2).isEmpty());
        assertEquals(OtClient.State.SYNCED, c.state());
        assertEquals(2, c.revision());
    }

    @Test
    void clientAppliesRemoteOpWhenSynced() {
        OtClient c = new OtClient("abc");
        c.serverOp(1, ins(3, 0, "Q"));
        assertEquals("Qabc", c.document());
        assertEquals(1, c.revision());
        assertEquals(OtClient.State.SYNCED, c.state());
    }

    @Test
    void clientRejectsOutOfOrderRevisionsWithoutChange() {
        OtClient c = new OtClient("abc");
        assertThrows(IllegalStateException.class, () -> c.serverOp(2, ins(3, 0, "Q")));
        assertThrows(IllegalStateException.class, () -> c.serverOp(0, ins(3, 0, "Q")));
        assertEquals("abc", c.document());
        assertEquals(0, c.revision());
        assertThrows(IllegalStateException.class, () -> c.serverAck(1));   // nothing in flight
        c.localEdit(ins(3, 0, "A"));
        assertThrows(IllegalStateException.class, () -> c.serverAck(2));   // wrong revision
        assertEquals(OtClient.State.AWAITING_ACK, c.state());
        assertEquals(0, c.revision());
    }

    @Test
    void clientRejectsWrongLengthLocalEditWithoutChange() {
        OtClient c = new OtClient("abc");
        assertThrows(IllegalArgumentException.class, () -> c.localEdit(TextOp.identity(2)));
        assertEquals(OtClient.State.SYNCED, c.state());
        assertEquals("abc", c.document());
    }

    @Test
    void twoClientsTypingAtTheSamePlaceConverge() {
        OtServer s = new OtServer("abc");
        OtClient a = new OtClient("abc");
        OtClient b = new OtClient("abc");
        OtClient.Message ma = a.localEdit(ins(3, 1, "A")).orElseThrow();   // a: "aAbc"
        OtClient.Message mb = b.localEdit(ins(3, 1, "B")).orElseThrow();   // b: "aBbc"
        // a reaches the server first
        OtServer.Result ra = s.submit(ma.baseRevision(), ma.op());
        OtServer.Result rb = s.submit(mb.baseRevision(), mb.op());
        assertEquals("aABbc", s.document());
        assertTrue(a.serverAck(ra.revision()).isEmpty());
        a.serverOp(rb.revision(), rb.op());
        b.serverOp(ra.revision(), ra.op());
        assertTrue(b.serverAck(rb.revision()).isEmpty());
        assertEquals("aABbc", a.document());
        assertEquals("aABbc", b.document());
        assertEquals(OtClient.State.SYNCED, a.state());
        assertEquals(OtClient.State.SYNCED, b.state());
    }

    @Test
    void remoteOpTransformsOverOutstandingAndBuffer() {
        OtServer s = new OtServer("abcdef");
        OtClient a = new OtClient("abcdef");
        OtClient b = new OtClient("abcdef");
        // b commits a delete of "cd" first
        OtClient.Message mb = b.localEdit(del(6, 2, 4)).orElseThrow();
        OtServer.Result rb = s.submit(mb.baseRevision(), mb.op());
        assertEquals("abef", s.document());
        // a, unaware, has one op in flight and one buffered (insert inside the deleted range, then at the end)
        OtClient.Message ma = a.localEdit(ins(6, 3, "X")).orElseThrow();
        assertTrue(a.localEdit(ins(7, 7, "Z")).isEmpty());
        assertEquals("abcXdefZ", a.document());
        a.serverOp(rb.revision(), rb.op());                  // b's delete arrives
        assertEquals("abXefZ", a.document());
        OtServer.Result ra = s.submit(ma.baseRevision(), ma.op());
        assertEquals("abXef", s.document());
        OtClient.Message next = a.serverAck(ra.revision()).orElseThrow();
        assertEquals(2, next.baseRevision());
        OtServer.Result rz = s.submit(next.baseRevision(), next.op());
        assertEquals("abXefZ", s.document());
        assertEquals(OtClient.State.AWAITING_ACK, a.state());
        assertTrue(a.serverAck(rz.revision()).isEmpty());
        assertEquals(OtClient.State.SYNCED, a.state());
        assertEquals("abXefZ", a.document());
        // b sees: its own ack (rev 1), then a's two ops (rev 2, 3)
        assertTrue(b.serverAck(rb.revision()).isEmpty());
        b.serverOp(ra.revision(), ra.op());
        b.serverOp(rz.revision(), rz.op());
        assertEquals("abXefZ", b.document());
    }
}
