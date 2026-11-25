package ae.net;

import java.util.ArrayList;
import java.util.List;

/** Splits packets to fit an MTU. */
public final class Fragmenter {
    private Fragmenter() {
    }

    /**
     * Fragments {@code p} for a link with the given MTU. Options stay on the first fragment only (simplification).
     *
     * @throws FragmentationNeededException if the packet does not fit and has DF set
     * @throws IllegalArgumentException if fewer than 8 payload bytes fit per fragment
     */
    public static List<Ipv4Packet> fragment(Ipv4Packet p, int mtu) {
        int hl = p.headerLength();
        if (hl + p.payloadLength() <= mtu) {
            return List.of(p);
        }
        if (p.df()) {
            throw new FragmentationNeededException("packet of " + (hl + p.payloadLength()) + " bytes exceeds mtu " + mtu
                    + " and DF is set");
        }
        int firstChunk = (mtu - hl) / 8 * 8;
        int laterChunk = (mtu - 20) / 8 * 8;
        if (firstChunk < 8 || laterChunk < 8) {
            throw new IllegalArgumentException("mtu " + mtu + " leaves no room for 8 payload bytes");
        }
        byte[] data = p.payload();
        List<Ipv4Packet> out = new ArrayList<>();
        int pos = 0;
        while (pos < data.length) {
            boolean first = pos == 0;
            int chunk = first ? firstChunk : laterChunk;
            int end = Math.min(data.length, pos + chunk);
            boolean more = end < data.length || p.mf();
            byte[] piece = java.util.Arrays.copyOfRange(data, pos, end);
            out.add(new Ipv4Packet(p.tos(), p.id(), false, more, p.fragOffset() + pos / 8, p.ttl(), p.protocol(),
                    p.src(), p.dst(), first ? p.options() : new byte[0], piece));
            pos = end;
        }
        return out;
    }
}
