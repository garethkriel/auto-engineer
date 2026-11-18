package ae.udp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PacketTest {
    @Test
    void dataRoundTrip() {
        Packet p = Packet.data(7, true, new byte[] {1, 2, 3});
        byte[] wire = p.encode();
        assertEquals(Packet.HEADER_SIZE + 3, wire.length);
        Packet q = Packet.decode(wire, wire.length).orElseThrow();
        assertEquals(Packet.Type.DATA, q.type());
        assertEquals(7, q.seq());
        assertTrue(q.last());
        assertArrayEquals(new byte[] {1, 2, 3}, q.payload());
    }

    @Test
    void ackRoundTrip() {
        byte[] wire = Packet.ack(Integer.MAX_VALUE).encode();
        assertEquals(Packet.HEADER_SIZE, wire.length);
        Packet q = Packet.decode(wire, wire.length).orElseThrow();
        assertEquals(Packet.Type.ACK, q.type());
        assertEquals(Integer.MAX_VALUE, q.seq());
        assertEquals(0, q.payload().length);
    }

    @Test
    void decodeHonoursLengthNotBufferSize() {
        byte[] wire = Packet.data(1, false, new byte[] {9}).encode();
        byte[] big = new byte[100];
        System.arraycopy(wire, 0, big, 0, wire.length);
        Packet q = Packet.decode(big, wire.length).orElseThrow();
        assertArrayEquals(new byte[] {9}, q.payload());
    }

    @Test
    void garbageIsRejected() {
        byte[] good = Packet.data(1, false, new byte[] {9}).encode();
        byte[] badMagic = good.clone();
        badMagic[0] = 0;
        byte[] badType = good.clone();
        badType[1] = 42;
        assertEquals(Optional.empty(), Packet.decode(badMagic, badMagic.length));
        assertEquals(Optional.empty(), Packet.decode(badType, badType.length));
        assertEquals(Optional.empty(), Packet.decode(good, Packet.HEADER_SIZE - 1));
        assertEquals(Optional.empty(), Packet.decode(new byte[0], 0));
        byte[] ackWithPayload = Packet.ack(1).encode();
        byte[] longer = java.util.Arrays.copyOf(ackWithPayload, ackWithPayload.length + 1);
        assertEquals(Optional.empty(), Packet.decode(longer, longer.length));
    }

    @Test
    void negativeSeqRejectedByFactory() {
        assertThrows(IllegalArgumentException.class, () -> Packet.ack(-1));
        assertThrows(IllegalArgumentException.class, () -> Packet.data(-1, false, new byte[0]));
    }
}
