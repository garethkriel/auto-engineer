package ae.proto;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Encoder and strict decoder for the protobuf wire format over {@link Msg}. */
public final class Codec {
    static final int MAX_DEPTH = 32;

    private Codec() {}

    // ---- encoding ----

    public static byte[] encode(Msg m) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        encodeInto(o, m);
        return o.toByteArray();
    }

    private static void tag(ByteArrayOutputStream o, int number, int wire) {
        Varint.write(o, ((long) number << 3) | wire);
    }

    private static void encodeInto(ByteArrayOutputStream o, Msg m) {
        for (Field f : m.type().fields()) {
            List<Object> vs = m.valueMap().get(f.number());
            if (vs == null || vs.isEmpty()) continue;
            if (f.packed()) {
                ByteArrayOutputStream payload = new ByteArrayOutputStream();
                for (Object v : vs) writeScalar(payload, f.kind(), v);
                tag(o, f.number(), 2);
                Varint.write(o, payload.size());
                o.writeBytes(payload.toByteArray());
            } else {
                for (Object v : vs) {
                    tag(o, f.number(), f.kind().wireType());
                    writeScalar(o, f.kind(), v);
                }
            }
        }
        for (Bytes u : m.unknownFields()) o.writeBytes(u.raw());
    }

    private static void le(ByteArrayOutputStream o, long v, int n) {
        for (int i = 0; i < n; i++) o.write((int) (v >>> (8 * i)) & 0xFF);
    }

    /** Writes the value of a field without its tag (length-prefixed for string/bytes/message). */
    private static void writeScalar(ByteArrayOutputStream o, Kind k, Object v) {
        switch (k) {
            case INT32 -> Varint.write(o, (Integer) v); // sign-extended to 64 bits
            case INT64, UINT64 -> Varint.write(o, (Long) v);
            case UINT32 -> Varint.write(o, (Long) v);
            case SINT32 -> Varint.write(o, Varint.zigzag32((Integer) v));
            case SINT64 -> Varint.write(o, Varint.zigzag64((Long) v));
            case BOOL -> o.write((Boolean) v ? 1 : 0);
            case FIXED32 -> le(o, (Long) v, 4);
            case SFIXED32 -> le(o, (Integer) v, 4);
            case FIXED64, SFIXED64 -> le(o, (Long) v, 8);
            case FLOAT -> le(o, Float.floatToRawIntBits((Float) v), 4);
            case DOUBLE -> le(o, Double.doubleToRawLongBits((Double) v), 8);
            case STRING -> lenPrefixed(o, Kind.utf8((String) v));
            case BYTES -> lenPrefixed(o, ((Bytes) v).raw());
            case MESSAGE -> lenPrefixed(o, encode((Msg) v));
        }
    }

    private static void lenPrefixed(ByteArrayOutputStream o, byte[] payload) {
        Varint.write(o, payload.length);
        o.writeBytes(payload);
    }

    // ---- decoding ----

    public static Msg decode(MessageType type, byte[] buf) {
        return decodeMessage(type, buf, 0, buf.length, 0);
    }

    private static Msg decodeMessage(MessageType type, byte[] buf, int start, int end, int depth) {
        Map<Integer, List<Object>> values = new HashMap<>();
        List<Bytes> unknown = new ArrayList<>();
        int pos = start;
        while (pos < end) {
            int fieldStart = pos;
            Varint.Read t = Varint.read(buf, pos, end);
            long tag = t.value();
            if (tag < 0 || tag > 0xFFFFFFFFL) throw new DecodeException("tag exceeds 32 bits", pos);
            int number = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (number == 0) throw new DecodeException("field number 0", pos);
            if (wire == 3 || wire == 4 || wire > 5) throw new DecodeException("invalid wire type " + wire, pos);
            pos = t.next();
            Field f = type.field(number);
            long varint = 0;
            int payloadFrom = pos;
            int payloadLen = 0;
            switch (wire) {
                case 0 -> {
                    Varint.Read r = Varint.read(buf, pos, end);
                    varint = r.value();
                    pos = r.next();
                }
                case 1 -> pos = need(buf, pos, end, 8, "fixed64");
                case 5 -> pos = need(buf, pos, end, 4, "fixed32");
                default -> {
                    Varint.Read r = Varint.read(buf, pos, end);
                    long len = r.value();
                    if (len < 0 || len > end - r.next()) {
                        throw new DecodeException("length " + Long.toUnsignedString(len)
                                + " exceeds remaining " + (end - r.next()) + " bytes", pos);
                    }
                    payloadFrom = r.next();
                    payloadLen = (int) len;
                    pos = payloadFrom + payloadLen;
                }
            }
            if (f == null || (wire != f.kind().wireType() && !(f.packed() && wire == 2))) {
                unknown.add(Bytes.slice(buf, fieldStart, pos));
                continue;
            }
            List<Object> slot = values.computeIfAbsent(number, k -> new ArrayList<>());
            if (wire == f.kind().wireType()) {
                Object v = f.kind() == Kind.MESSAGE || f.kind() == Kind.STRING || f.kind() == Kind.BYTES
                        ? lengthValue(f, buf, payloadFrom, payloadLen, depth)
                        : scalar(f.kind(), buf, wire == 0 ? 0 : payloadOrFixedStart(pos, wire), varint);
                if (f.repeated()) slot.add(v);
                else {
                    slot.clear();
                    slot.add(v);
                }
            } else {
                // packed payload of numeric scalars
                int q = payloadFrom;
                int pend = payloadFrom + payloadLen;
                int w = f.kind().wireType();
                while (q < pend) {
                    if (w == 0) {
                        Varint.Read r = Varint.read(buf, q, pend);
                        slot.add(scalar(f.kind(), buf, 0, r.value()));
                        q = r.next();
                    } else {
                        int size = w == 5 ? 4 : 8;
                        if (pend - q < size) {
                            throw new DecodeException("packed payload is not a whole number of elements", q);
                        }
                        slot.add(scalar(f.kind(), buf, q, 0));
                        q += size;
                    }
                }
            }
        }
        return Msg.build(type, values, unknown);
    }

    private static int payloadOrFixedStart(int posAfter, int wire) {
        return posAfter - (wire == 5 ? 4 : 8);
    }

    private static int need(byte[] buf, int pos, int end, int n, String what) {
        if (end - pos < n) throw new DecodeException("truncated " + what, pos);
        return pos + n;
    }

    private static Object lengthValue(Field f, byte[] buf, int from, int len, int depth) {
        return switch (f.kind()) {
            case STRING -> Kind.decodeUtf8(buf, from, len, from);
            case BYTES -> Bytes.slice(buf, from, from + len);
            default -> {
                if (depth + 1 > MAX_DEPTH) throw new DecodeException("nesting depth exceeds " + MAX_DEPTH, from);
                yield decodeMessage(f.message(), buf, from, from + len, depth + 1);
            }
        };
    }

    /** A varint-wire kind uses {@code varint}; a fixed-width kind reads little-endian at {@code at}. */
    private static Object scalar(Kind k, byte[] buf, int at, long varint) {
        return switch (k) {
            case INT32 -> (int) varint;
            case INT64, UINT64 -> varint;
            case UINT32 -> varint & 0xFFFFFFFFL;
            case SINT32 -> Varint.unzigzag32(varint);
            case SINT64 -> Varint.unzigzag64(varint);
            case BOOL -> varint != 0;
            case FIXED32 -> fixed(buf, at, 4).getInt() & 0xFFFFFFFFL;
            case SFIXED32 -> fixed(buf, at, 4).getInt();
            case FIXED64, SFIXED64 -> fixed(buf, at, 8).getLong();
            case FLOAT -> fixed(buf, at, 4).getFloat();
            case DOUBLE -> fixed(buf, at, 8).getDouble();
            default -> throw new IllegalStateException("not a scalar kind " + k);
        };
    }

    private static ByteBuffer fixed(byte[] buf, int at, int n) {
        return ByteBuffer.wrap(buf, at, n).order(ByteOrder.LITTLE_ENDIAN);
    }
}
