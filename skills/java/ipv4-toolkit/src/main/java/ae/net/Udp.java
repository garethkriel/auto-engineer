package ae.net;

import java.util.Arrays;

/** UDP over IPv4 with the pseudo-header checksum. */
public final class Udp {
    private static final int HEADER = 8;
    private static final int PROTOCOL = 17;

    /** A decoded UDP datagram. */
    public record Datagram(int srcPort, int dstPort, byte[] payload) {
    }

    private Udp() {
    }

    public static byte[] encode(int srcIp, int dstIp, int srcPort, int dstPort, byte[] payload) {
        if (srcPort < 0 || srcPort > 0xffff || dstPort < 0 || dstPort > 0xffff) {
            throw new IllegalArgumentException("port out of range");
        }
        int total = HEADER + payload.length;
        if (total > 0xffff) {
            throw new IllegalArgumentException("datagram too long: " + total);
        }
        byte[] seg = new byte[total];
        Ipv4Packet.put16(seg, 0, srcPort);
        Ipv4Packet.put16(seg, 2, dstPort);
        Ipv4Packet.put16(seg, 4, total);
        System.arraycopy(payload, 0, seg, HEADER, payload.length);
        int c = checksum(srcIp, dstIp, seg);
        Ipv4Packet.put16(seg, 6, c == 0 ? 0xffff : c);
        return seg;
    }

    public static Datagram decode(int srcIp, int dstIp, byte[] seg) {
        if (seg.length < HEADER) {
            throw new PacketException("short udp datagram");
        }
        if (Ipv4Packet.get16(seg, 4) != seg.length) {
            throw new PacketException("udp length field " + Ipv4Packet.get16(seg, 4) + " != " + seg.length);
        }
        if (Ipv4Packet.get16(seg, 6) != 0 && checksum(srcIp, dstIp, seg) != 0) {
            throw new PacketException("bad udp checksum");
        }
        return new Datagram(Ipv4Packet.get16(seg, 0), Ipv4Packet.get16(seg, 2), Arrays.copyOfRange(seg, HEADER, seg.length));
    }

    private static int checksum(int srcIp, int dstIp, byte[] seg) {
        byte[] buf = new byte[12 + seg.length];
        Ipv4Packet.put32(buf, 0, srcIp);
        Ipv4Packet.put32(buf, 4, dstIp);
        buf[9] = PROTOCOL;
        Ipv4Packet.put16(buf, 10, seg.length);
        System.arraycopy(seg, 0, buf, 12, seg.length);
        return Checksum.of(buf, 0, buf.length);
    }
}
