package ae.dns;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** RFC 1035 wire codec with name compression on encode and a strict decoder. */
public final class Wire {
    private static final int HEADER = 12;
    private static final int CLASS_IN = 1;
    private static final int MAX_POINTER_OFFSET = 0x3FFF;

    private Wire() {
    }

    // ---- encoding ------------------------------------------------------------

    public static byte[] encode(Message m) {
        Encoder e = new Encoder();
        e.u16(m.id());
        int flags = (m.response() ? 0x8000 : 0) | (m.aa() ? 0x0400 : 0) | (m.tc() ? 0x0200 : 0)
                | (m.rd() ? 0x0100 : 0) | (m.ra() ? 0x0080 : 0) | m.rcode().code();
        e.u16(flags);
        e.u16(1);
        e.u16(count(m.answers()));
        e.u16(count(m.authority()));
        e.u16(count(m.additional()));
        e.name(m.qname());
        e.u16(m.qtype().code());
        e.u16(CLASS_IN);
        for (Rr r : m.answers()) {
            e.rr(r);
        }
        for (Rr r : m.authority()) {
            e.rr(r);
        }
        for (Rr r : m.additional()) {
            e.rr(r);
        }
        return Arrays.copyOf(e.buf, e.len);
    }

    private static int count(List<Rr> l) {
        if (l.size() > 0xFFFF) {
            throw new IllegalArgumentException("too many records: " + l.size());
        }
        return l.size();
    }

    private static final class Encoder {
        byte[] buf = new byte[512];
        int len;
        final Map<String, Integer> offsets = new HashMap<>();

        void ensure(int extra) {
            if (len + extra > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
            }
        }

        void u8(int v) {
            ensure(1);
            buf[len++] = (byte) v;
        }

        void u16(int v) {
            u8(v >>> 8);
            u8(v);
        }

        void u32(long v) {
            u16((int) (v >>> 16));
            u16((int) v);
        }

        void name(Name n) {
            List<String> labels = n.labels();
            for (int i = 0; i < labels.size(); i++) {
                String key = String.join(".", labels.subList(i, labels.size()));
                Integer off = offsets.get(key);
                if (off != null) {
                    u16(0xC000 | off);
                    return;
                }
                if (len <= MAX_POINTER_OFFSET) {
                    offsets.put(key, len);
                }
                byte[] b = labels.get(i).getBytes(StandardCharsets.ISO_8859_1);
                u8(b.length);
                ensure(b.length);
                System.arraycopy(b, 0, buf, len, b.length);
                len += b.length;
            }
            u8(0);
        }

        void rr(Rr r) {
            name(r.name());
            u16(r.type().code());
            u16(CLASS_IN);
            u32(r.ttl());
            int lenPos = len;
            u16(0);
            switch (r.data()) {
                case RData.A a -> u32(a.address() & 0xFFFFFFFFL);
                case RData.Aaaa a -> {
                    u32(a.hi() >>> 32);
                    u32(a.hi() & 0xFFFFFFFFL);
                    u32(a.lo() >>> 32);
                    u32(a.lo() & 0xFFFFFFFFL);
                }
                case RData.Ns ns -> name(ns.host());
                case RData.Cname c -> name(c.target());
                case RData.Mx mx -> {
                    u16(mx.preference());
                    name(mx.host());
                }
                case RData.Txt t -> {
                    for (String chunk : t.chunks()) {
                        byte[] b = chunk.getBytes(StandardCharsets.ISO_8859_1);
                        u8(b.length);
                        ensure(b.length);
                        System.arraycopy(b, 0, buf, len, b.length);
                        len += b.length;
                    }
                }
                case RData.Soa s -> {
                    name(s.mname());
                    name(s.rname());
                    u32(s.serial());
                    u32(s.refresh());
                    u32(s.retry());
                    u32(s.expire());
                    u32(s.minimum());
                }
            }
            int rdlen = len - lenPos - 2;
            if (rdlen > 0xFFFF) {
                throw new IllegalArgumentException("rdata too long");
            }
            buf[lenPos] = (byte) (rdlen >>> 8);
            buf[lenPos + 1] = (byte) rdlen;
        }
    }

    // ---- decoding ------------------------------------------------------------

    /** Decodes a message; throws only {@link DecodeException}. */
    public static Message decode(byte[] bytes) {
        try {
            return new Decoder(bytes).message();
        } catch (IllegalArgumentException e) {
            // value-object validation (e.g. a label with a forbidden character) surfaces as a decode error
            throw new DecodeException("invalid value: " + e.getMessage());
        }
    }

    private static final class Decoder {
        final byte[] b;
        int pos;

        Decoder(byte[] b) {
            this.b = b;
        }

        void need(int at, int n) {
            if (at < 0 || n < 0 || at + n > b.length) {
                throw new DecodeException("truncated message at offset " + at);
            }
        }

        int u8() {
            need(pos, 1);
            return b[pos++] & 0xFF;
        }

        int u16() {
            need(pos, 2);
            int v = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        long u32() {
            long hi = u16();
            return (hi << 16) | u16();
        }

        Message message() {
            if (b.length < HEADER) {
                throw new DecodeException("truncated header");
            }
            int id = u16();
            int flags = u16();
            int qd = u16();
            int an = u16();
            int ns = u16();
            int ar = u16();
            if (((flags >>> 11) & 0xF) != 0) {
                throw new DecodeException("unsupported opcode " + ((flags >>> 11) & 0xF));
            }
            if ((flags & 0x0070) != 0) {
                throw new DecodeException("reserved flags set");
            }
            Rcode rcode = Rcode.fromCode(flags & 0xF);
            if (rcode == null) {
                throw new DecodeException("unsupported rcode " + (flags & 0xF));
            }
            if (qd != 1) {
                throw new DecodeException("expected exactly one question, got " + qd);
            }
            Name qname = readName();
            RType qtype = type(u16());
            if (u16() != CLASS_IN) {
                throw new DecodeException("unsupported class in question");
            }
            List<Rr> answers = records(an);
            List<Rr> authority = records(ns);
            List<Rr> additional = records(ar);
            if (pos != b.length) {
                throw new DecodeException("trailing bytes after message: " + (b.length - pos));
            }
            return new Message(id, (flags & 0x8000) != 0, (flags & 0x0400) != 0, (flags & 0x0200) != 0,
                    (flags & 0x0100) != 0, (flags & 0x0080) != 0, rcode, qname, qtype, answers, authority, additional);
        }

        RType type(int code) {
            RType t = RType.fromCode(code);
            if (t == null) {
                throw new DecodeException("unsupported type " + code);
            }
            return t;
        }

        List<Rr> records(int count) {
            List<Rr> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                out.add(record());
            }
            return out;
        }

        Rr record() {
            Name owner = readName();
            RType t = type(u16());
            if (u16() != CLASS_IN) {
                throw new DecodeException("unsupported class in record");
            }
            long ttl = u32();
            if (ttl > Rr.MAX_TTL) {
                throw new DecodeException("ttl has the high bit set");
            }
            int rdlen = u16();
            need(pos, rdlen);
            int end = pos + rdlen;
            RData data = switch (t) {
                case A -> {
                    fixed(rdlen, 4);
                    yield new RData.A((int) u32());
                }
                case AAAA -> {
                    fixed(rdlen, 16);
                    long hi = (u32() << 32) | u32();
                    long lo = (u32() << 32) | u32();
                    yield new RData.Aaaa(hi, lo);
                }
                case NS -> new RData.Ns(readNameWithin(end));
                case CNAME -> new RData.Cname(readNameWithin(end));
                case MX -> {
                    if (rdlen < 3) {
                        throw new DecodeException("bad rdlength for MX");
                    }
                    int pref = u16();
                    yield new RData.Mx(pref, readNameWithin(end));
                }
                case TXT -> {
                    List<String> chunks = new ArrayList<>();
                    while (pos < end) {
                        int l = u8();
                        if (pos + l > end) {
                            throw new DecodeException("TXT chunk runs past rdlength");
                        }
                        chunks.add(new String(b, pos, l, StandardCharsets.ISO_8859_1));
                        pos += l;
                    }
                    if (chunks.isEmpty()) {
                        throw new DecodeException("bad rdlength for TXT (empty)");
                    }
                    yield new RData.Txt(chunks);
                }
                case SOA -> {
                    Name mname = readNameWithin(end);
                    Name rname = readNameWithin(end);
                    if (end - pos != 20) {
                        throw new DecodeException("bad rdlength for SOA");
                    }
                    yield new RData.Soa(mname, rname, u32(), u32(), u32(), u32(), u32());
                }
            };
            if (pos != end) {
                throw new DecodeException("rdlength mismatch for " + t);
            }
            return new Rr(owner, ttl, data);
        }

        void fixed(int rdlen, int expected) {
            if (rdlen != expected) {
                throw new DecodeException("bad rdlength " + rdlen + ", expected " + expected);
            }
        }

        /** Reads a name inside rdata; the encoded name must not extend past {@code end}. */
        Name readNameWithin(int end) {
            Name n = readName();
            if (pos > end) {
                throw new DecodeException("rdlength too short for embedded name");
            }
            return n;
        }

        Name readName() {
            List<String> labels = new ArrayList<>();
            int start = pos;
            int p = pos;
            int resume = -1;
            int total = 1;
            while (true) {
                need(p, 1);
                int len = b[p] & 0xFF;
                if (len == 0) {
                    p++;
                    break;
                }
                int kind = len & 0xC0;
                if (kind == 0xC0) {
                    need(p, 2);
                    int target = ((len & 0x3F) << 8) | (b[p + 1] & 0xFF);
                    if (target >= start) {
                        throw new DecodeException("compression pointer must point before the name (offset " + p + ")");
                    }
                    if (resume < 0) {
                        resume = p + 2;
                    }
                    p = target;
                    continue;
                }
                if (kind != 0) {
                    throw new DecodeException("unsupported label type at offset " + p);
                }
                p++;
                need(p, len);
                total += len + 1;
                if (total > Name.MAX_WIRE) {
                    throw new DecodeException("name longer than 255 bytes");
                }
                labels.add(new String(b, p, len, StandardCharsets.ISO_8859_1));
                p += len;
            }
            pos = resume >= 0 ? resume : p;
            return Name.of(labels);
        }
    }
}
