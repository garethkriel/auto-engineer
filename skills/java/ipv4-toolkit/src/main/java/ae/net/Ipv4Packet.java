package ae.net;

import java.util.Arrays;

/** Immutable IPv4 packet (or fragment) with value equality. Addresses are 32-bit ints. */
public final class Ipv4Packet {
    public static final int MAX_TOTAL_LENGTH = 65535;
    private static final int BASE_HEADER = 20;

    private final int tos;
    private final int id;
    private final boolean df;
    private final boolean mf;
    private final int fragOffset;
    private final int ttl;
    private final int protocol;
    private final int src;
    private final int dst;
    private final byte[] options;
    private final byte[] payload;

    public Ipv4Packet(int tos, int id, boolean df, boolean mf, int fragOffset, int ttl, int protocol, int src, int dst,
            byte[] options, byte[] payload) {
        require(tos >= 0 && tos <= 0xff, "tos");
        require(id >= 0 && id <= 0xffff, "id");
        require(fragOffset >= 0 && fragOffset <= 0x1fff, "fragOffset");
        require(ttl >= 0 && ttl <= 0xff, "ttl");
        require(protocol >= 0 && protocol <= 0xff, "protocol");
        require(options != null && options.length % 4 == 0 && options.length <= 40, "options");
        require(payload != null, "payload");
        this.tos = tos;
        this.id = id;
        this.df = df;
        this.mf = mf;
        this.fragOffset = fragOffset;
        this.ttl = ttl;
        this.protocol = protocol;
        this.src = src;
        this.dst = dst;
        this.options = options.clone();
        this.payload = payload.clone();
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("invalid " + what);
        }
    }

    public int tos() {
        return tos;
    }

    public int id() {
        return id;
    }

    public boolean df() {
        return df;
    }

    public boolean mf() {
        return mf;
    }

    /** Offset in units of 8 bytes. */
    public int fragOffset() {
        return fragOffset;
    }

    public int ttl() {
        return ttl;
    }

    public int protocol() {
        return protocol;
    }

    public int src() {
        return src;
    }

    public int dst() {
        return dst;
    }

    public byte[] options() {
        return options.clone();
    }

    public byte[] payload() {
        return payload.clone();
    }

    int payloadLength() {
        return payload.length;
    }

    int headerLength() {
        return BASE_HEADER + options.length;
    }

    /** Wire form with computed IHL, total length and header checksum. */
    public byte[] encode() {
        int hl = headerLength();
        int total = hl + payload.length;
        if (total > MAX_TOTAL_LENGTH) {
            throw new IllegalArgumentException("total length " + total + " exceeds 65535");
        }
        byte[] w = new byte[total];
        w[0] = (byte) (0x40 | (hl / 4));
        w[1] = (byte) tos;
        put16(w, 2, total);
        put16(w, 4, id);
        put16(w, 6, (df ? 0x4000 : 0) | (mf ? 0x2000 : 0) | fragOffset);
        w[8] = (byte) ttl;
        w[9] = (byte) protocol;
        put32(w, 12, src);
        put32(w, 16, dst);
        System.arraycopy(options, 0, w, BASE_HEADER, options.length);
        put16(w, 10, Checksum.of(w, 0, hl));
        System.arraycopy(payload, 0, w, hl, payload.length);
        return w;
    }

    /** Strict decoder: throws {@link PacketException} for anything malformed. */
    public static Ipv4Packet decode(byte[] w) {
        if (w.length < BASE_HEADER) {
            throw new PacketException("short packet: " + w.length + " bytes");
        }
        if ((w[0] >> 4 & 0x0f) != 4) {
            throw new PacketException("bad version " + (w[0] >> 4 & 0x0f));
        }
        int ihl = w[0] & 0x0f;
        if (ihl < 5) {
            throw new PacketException("bad ihl " + ihl);
        }
        int hl = ihl * 4;
        if (hl > w.length) {
            throw new PacketException("header longer than data: " + hl + " > " + w.length);
        }
        int total = get16(w, 2);
        if (total < hl || total > w.length) {
            throw new PacketException("bad total length " + total);
        }
        if (Checksum.of(w, 0, hl) != 0) {
            throw new PacketException("bad header checksum");
        }
        int flags = get16(w, 6);
        if ((flags & 0x8000) != 0) {
            throw new PacketException("reserved flag set");
        }
        boolean df = (flags & 0x4000) != 0;
        boolean mf = (flags & 0x2000) != 0;
        int off = flags & 0x1fff;
        if (df && (mf || off != 0)) {
            throw new PacketException("inconsistent flags: DF with fragmentation fields");
        }
        return new Ipv4Packet(w[1] & 0xff, get16(w, 4), df, mf, off, w[8] & 0xff, w[9] & 0xff, get32(w, 12),
                get32(w, 16), Arrays.copyOfRange(w, BASE_HEADER, hl), Arrays.copyOfRange(w, hl, total));
    }

    static void put16(byte[] b, int at, int v) {
        b[at] = (byte) (v >> 8);
        b[at + 1] = (byte) v;
    }

    static void put32(byte[] b, int at, int v) {
        put16(b, at, v >>> 16);
        put16(b, at + 2, v & 0xffff);
    }

    static int get16(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    static int get32(byte[] b, int at) {
        return (get16(b, at) << 16) | get16(b, at + 2);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Ipv4Packet p)) {
            return false;
        }
        return tos == p.tos && id == p.id && df == p.df && mf == p.mf && fragOffset == p.fragOffset && ttl == p.ttl
                && protocol == p.protocol && src == p.src && dst == p.dst && Arrays.equals(options, p.options)
                && Arrays.equals(payload, p.payload);
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(payload) * 31 + Arrays.hashCode(options);
        return h * 31 + (((((tos * 31 + id) * 31 + fragOffset) * 31 + ttl) * 31 + protocol) * 31 + src) * 31 + dst
                + (df ? 1 : 0) + (mf ? 2 : 0);
    }

    @Override
    public String toString() {
        return "Ipv4Packet[id=" + id + (df ? " DF" : "") + (mf ? " MF" : "") + " off=" + fragOffset + " len="
                + payload.length + "]";
    }
}
