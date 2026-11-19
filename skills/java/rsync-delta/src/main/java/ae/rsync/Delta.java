package ae.rsync;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Instructions that rebuild the target from the basis: copy ranges of the basis and literal bytes.
 * Carries the target's length and SHA-256 so the receiver can verify the result.
 */
public record Delta(List<Op> ops, long targetLength, byte[] targetSha256) {
    private static final byte[] MAGIC = {'R', 'S', 'D', '1'};
    private static final int TAG_COPY = 0;
    private static final int TAG_LITERAL = 1;
    private static final int SHA = 32;

    /** One instruction. */
    public sealed interface Op permits Copy, Literal {
        /** Number of target bytes this op produces. */
        long size();
    }

    /** Copy {@code length} bytes starting at {@code offset} of the basis. */
    public record Copy(long offset, int length) implements Op {
        public Copy {
            if (offset < 0 || length <= 0) {
                throw new IllegalArgumentException("bad copy offset=" + offset + " length=" + length);
            }
        }

        @Override
        public long size() {
            return length;
        }
    }

    /** Raw bytes to insert. */
    public record Literal(byte[] data) implements Op {
        public Literal {
            Objects.requireNonNull(data, "data");
            if (data.length == 0) {
                throw new IllegalArgumentException("empty literal");
            }
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Literal l && Arrays.equals(l.data, data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }

    public Delta {
        ops = List.copyOf(ops);
        Objects.requireNonNull(targetSha256, "targetSha256");
        if (targetSha256.length != SHA) {
            throw new IllegalArgumentException("target digest must be 32 bytes");
        }
        targetSha256 = targetSha256.clone();
        long sum = 0;
        for (Op op : ops) {
            sum += op.size();
        }
        if (sum != targetLength) {
            throw new IllegalArgumentException("target length " + targetLength + " != sum of ops " + sum);
        }
    }

    @Override
    public byte[] targetSha256() {
        return targetSha256.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Delta d && d.targetLength == targetLength && d.ops.equals(ops)
                && Arrays.equals(d.targetSha256, targetSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ops, targetLength) * 31 + Arrays.hashCode(targetSha256);
    }

    public static byte[] sha256(byte[] data) {
        return DigestSupport.sha256().digest(data);
    }

    public byte[] toBytes() {
        int size = 4 + 4 + 8 + SHA + 4;
        for (Op op : ops) {
            size += op instanceof Copy ? 1 + 8 + 4 : 1 + 4 + ((Literal) op).data.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(MAGIC).putInt(ops.size());
        for (Op op : ops) {
            if (op instanceof Copy c) {
                buf.put((byte) TAG_COPY).putLong(c.offset()).putInt(c.length());
            } else {
                Literal l = (Literal) op;
                buf.put((byte) TAG_LITERAL).putInt(l.data.length).put(l.data);
            }
        }
        buf.putLong(targetLength).put(targetSha256);
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, buf.position());
        buf.putInt((int) crc.getValue());
        return buf.array();
    }

    public static Delta parse(byte[] wire) throws FormatException {
        Objects.requireNonNull(wire, "wire");
        if (wire.length < 4 + 4 + 8 + SHA + 4) {
            throw new FormatException("delta truncated");
        }
        CRC32 crc = new CRC32();
        crc.update(wire, 0, wire.length - 4);
        if ((int) crc.getValue() != ByteBuffer.wrap(wire, wire.length - 4, 4).getInt()) {
            throw new FormatException("delta checksum mismatch");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire, 0, wire.length - 4);
        try {
            byte[] magic = new byte[4];
            buf.get(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new FormatException("bad delta magic");
            }
            long count = buf.getInt() & 0xFFFFFFFFL;
            if (count > buf.remaining()) {
                throw new FormatException("op count " + count + " exceeds payload");
            }
            List<Op> ops = new ArrayList<>();
            for (long i = 0; i < count; i++) {
                int tag = buf.get();
                if (tag == TAG_COPY) {
                    long offset = buf.getLong();
                    int length = buf.getInt();
                    ops.add(new Copy(offset, length));
                } else if (tag == TAG_LITERAL) {
                    int length = buf.getInt();
                    if (length <= 0 || length > buf.remaining()) {
                        throw new FormatException("literal length " + length + " exceeds remaining bytes");
                    }
                    byte[] data = new byte[length];
                    buf.get(data);
                    ops.add(new Literal(data));
                } else {
                    throw new FormatException("unknown op tag " + tag);
                }
            }
            long targetLength = buf.getLong();
            byte[] sha = new byte[SHA];
            buf.get(sha);
            if (buf.hasRemaining()) {
                throw new FormatException("trailing bytes after delta");
            }
            return new Delta(ops, targetLength, sha);
        } catch (BufferUnderflowException e) {
            throw new FormatException("delta truncated");
        } catch (IllegalArgumentException e) {
            throw new FormatException("invalid delta: " + e.getMessage());
        }
    }
}
