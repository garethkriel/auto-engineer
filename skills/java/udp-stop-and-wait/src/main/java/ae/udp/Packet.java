package ae.udp;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Wire format of the stop-and-wait protocol:
 * {@code [magic 0xAE][type 1=DATA,2=ACK][seq int32 big-endian][flags][payload...]}.
 * Flag bit 0 marks the last DATA packet of a message. ACKs carry no payload.
 */
public final class Packet {
    /** Size of the fixed header in bytes. */
    public static final int HEADER_SIZE = 7;
    private static final byte MAGIC = (byte) 0xAE;
    private static final int FLAG_LAST = 1;

    /** Packet kinds. */
    public enum Type { DATA, ACK }

    private final Type type;
    private final int seq;
    private final boolean last;
    private final byte[] payload;

    private Packet(Type type, int seq, boolean last, byte[] payload) {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0: " + seq);
        }
        this.type = type;
        this.seq = seq;
        this.last = last;
        this.payload = payload;
    }

    /** Creates a DATA packet; the payload is copied. */
    public static Packet data(int seq, boolean last, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        return new Packet(Type.DATA, seq, last, payload.clone());
    }

    /** Creates an ACK for {@code seq}. */
    public static Packet ack(int seq) {
        return new Packet(Type.ACK, seq, false, new byte[0]);
    }

    public Type type() { return type; }

    public int seq() { return seq; }

    public boolean last() { return last; }

    /** Returns a copy of the payload. */
    public byte[] payload() { return payload.clone(); }

    /** Serialises this packet. */
    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        b.put(MAGIC).put((byte) (type == Type.DATA ? 1 : 2)).putInt(seq)
                .put((byte) (last ? FLAG_LAST : 0)).put(payload);
        return b.array();
    }

    /** Parses the first {@code length} bytes of {@code buf}; empty for anything malformed. */
    public static Optional<Packet> decode(byte[] buf, int length) {
        if (length < HEADER_SIZE || length > buf.length || buf[0] != MAGIC) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(buf, 0, length);
        b.get();
        byte t = b.get();
        int seq = b.getInt();
        byte flags = b.get();
        if (seq < 0 || (flags & ~FLAG_LAST) != 0) {
            return Optional.empty();
        }
        byte[] payload = Arrays.copyOfRange(buf, HEADER_SIZE, length);
        if (t == 1) {
            return Optional.of(new Packet(Type.DATA, seq, (flags & FLAG_LAST) != 0, payload));
        }
        if (t == 2 && payload.length == 0 && flags == 0) {
            return Optional.of(new Packet(Type.ACK, seq, false, payload));
        }
        return Optional.empty();
    }
}
