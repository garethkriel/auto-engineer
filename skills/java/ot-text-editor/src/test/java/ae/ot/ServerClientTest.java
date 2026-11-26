package ae.ot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerClientTest {

    static TextOp ins(int pos, int len, String s) {
        return new TextOp.Builder().retain(pos).insert(s).retain(len - pos).build();
    }

    @Test
    void serverTransformsStaleOpAgainstHistory() {
        Server s = new Server("abc");
        Server.Accepted a1 = s.receive("A", 0, ins(0, 3, "X")); // Xabc
        assertEquals(1, a1.revision());
        // B was based on rev 0 and inserts at 0 too: history wins ties => X then Y
        Server.Accepted a2 = s.receive("B", 0, ins(0, 3, "Y"));
        assertEquals(2, a2.revision());
        assertEquals("XYabc", s.document());
        assertEquals("[R1, I\"Y\", R3]", a2.op().toString());
    }

    @Test
    void serverRejectsBadBaseRevisionWithoutStateChange() {
        Server s = new Server("abc");
        s.receive("A", 0, ins(0, 3, "X"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.receive("B", 2, ins(0, 3, "Y")));
        assertTrue(e.getMessage().startsWith("bad base revision"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> s.receive("B", -1, ins(0, 3, "Y")));
        assertEquals(1, s.revision());
        assertEquals("Xabc", s.document());
    }

    @Test
    void serverRejectsWrongLengthWithoutStateChange() {
        Server s = new Server("abc");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.receive("B", 0, ins(0, 5, "Y")));
        assertTrue(e.getMessage().startsWith("op base length"), e.getMessage());
        assertEquals(0, s.revision());
        assertEquals("abc", s.document());
    }

    @Test
    void clientSynchronizedSendsImmediately() {
        Client c = new Client("A", "abc");
        Optional<Client.Send> send = c.local(ins(3, 3, "!"));
        assertEquals("abc!", c.document());
        assertTrue(send.isPresent());
        assertEquals(0, send.get().baseRevision());
        assertEquals(Client.State.AWAITING_ACK, c.state());
    }

    @Test
    void clientBuffersWhileAwaiting() {
        Client c = new Client("A", "abc");
        c.local(ins(0, 3, "1"));
        Optional<Client.Send> second = c.local(ins(4, 4, "2"));
        assertTrue(second.isEmpty());
        assertEquals(Client.State.AWAITING_ACK_WITH_BUFFER, c.state());
        assertEquals("1abc2", c.document());
        Optional<Client.Send> next = c.ack();
        assertTrue(next.isPresent());
        assertEquals(1, next.get().baseRevision());
        assertEquals(Client.State.AWAITING_ACK, c.state());
        assertEquals("[R4, I\"2\"]", next.get().op().toString());
        assertTrue(c.ack().isEmpty());
        assertEquals(Client.State.SYNCHRONIZED, c.state());
        assertEquals(2, c.revision());
    }

    @Test
    void clientRemoteOpTransformedOverOutstandingAndBuffer() {
        Client c = new Client("A", "abc");
        c.local(ins(0, 3, "1"));      // 1abc, out
        c.local(ins(4, 4, "2"));      // 1abc2, buffer
        // server op: insert "S" at 0 on base abc (tie with out: server first)
        c.remote(ins(0, 3, "S"));
        assertEquals("S1abc2", c.document());
        assertEquals(1, c.revision());
    }

    @Test
    void ackWhenSynchronizedIsRejected() {
        Client c = new Client("A", "abc");
        IllegalStateException e = assertThrows(IllegalStateException.class, c::ack);
        assertTrue(e.getMessage().startsWith("unexpected ack"), e.getMessage());
    }

    @Test
    void localOpWithWrongBaseLengthLeavesClientUnchanged() {
        Client c = new Client("A", "abc");
        assertThrows(IllegalArgumentException.class, () -> c.local(ins(0, 9, "x")));
        assertEquals("abc", c.document());
        assertEquals(Client.State.SYNCHRONIZED, c.state());
    }

    @Test
    void remoteOpWithWrongBaseLengthLeavesClientUnchanged() {
        Client c = new Client("A", "abc");
        c.local(ins(0, 3, "1"));
        assertThrows(IllegalArgumentException.class, () -> c.remote(ins(0, 9, "x")));
        assertEquals("1abc", c.document());
        assertEquals(0, c.revision());
    }
}
