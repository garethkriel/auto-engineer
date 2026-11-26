package ae.arch;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;

/** Writes classic (non-ZIP64) ZIP archives with a fixed timestamp, so output is deterministic. */
public final class ZipWriter {
    /** How file data is stored. Directories are always stored. */
    public enum Method {
        STORED, DEFLATED,
        /** Deflate only when that is strictly smaller than the raw data. */
        AUTO
    }

    static final int LOCAL_SIG = 0x04034b50;
    static final int CENTRAL_SIG = 0x02014b50;
    static final int EOCD_SIG = 0x06054b50;
    static final int LOCAL_SIZE = 30;
    static final int CENTRAL_SIZE = 46;
    static final int EOCD_SIZE = 22;
    static final int FLAG_UTF8 = 0x0800;
    static final int MAX_ENTRIES = 0xFFFE; // 0xFFFF is the ZIP64 marker
    static final int DOS_DATE_1980_01_01 = (0 << 9) | (1 << 5) | 1;

    private ZipWriter() {}

    private record Member(byte[] name, int method, long crc, byte[] stored, int size, int localOffset) {}

    public static byte[] write(List<ArchiveEntry> entries, Method method, String comment) {
        Objects.requireNonNull(entries);
        Objects.requireNonNull(method);
        byte[] cm = Names.utf8(Objects.requireNonNull(comment));
        if (cm.length > 0xFFFF) {
            throw new IllegalArgumentException("comment longer than 65535 bytes");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("more than 65534 entries (0xFFFF is the ZIP64 marker)");
        }
        Archives.checkTree(entries);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Member> members = new ArrayList<>();
        for (ArchiveEntry e : entries) {
            byte[] data = e.data();
            byte[] stored = data;
            int m = 0;
            if (!e.directory() && method != Method.STORED) {
                byte[] def = deflate(data);
                if (method == Method.DEFLATED || def.length < data.length) {
                    stored = def;
                    m = 8;
                }
            }
            byte[] name = Names.utf8(e.name());
            if (name.length > 0xFFFF) {
                throw new IllegalArgumentException("name longer than 65535 bytes");
            }
            Member mem = new Member(name, m, Crc32.of(data), stored, data.length, out.size());
            members.add(mem);
            ByteBuffer h = buffer(LOCAL_SIZE);
            h.putInt(LOCAL_SIG).putShort((short) version(m)).putShort((short) FLAG_UTF8).putShort((short) m)
                .putShort((short) 0).putShort((short) DOS_DATE_1980_01_01)
                .putInt((int) mem.crc).putInt(stored.length).putInt(mem.size)
                .putShort((short) name.length).putShort((short) 0);
            out.writeBytes(h.array());
            out.writeBytes(name);
            out.writeBytes(stored);
        }
        int cdStart = out.size();
        for (int i = 0; i < members.size(); i++) {
            Member mem = members.get(i);
            ByteBuffer h = buffer(CENTRAL_SIZE);
            h.putInt(CENTRAL_SIG).putShort((short) 20).putShort((short) version(mem.method)).putShort((short) FLAG_UTF8)
                .putShort((short) mem.method).putShort((short) 0).putShort((short) DOS_DATE_1980_01_01)
                .putInt((int) mem.crc).putInt(mem.stored.length).putInt(mem.size)
                .putShort((short) mem.name.length).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0)
                .putInt(entries.get(i).directory() ? 0x10 : 0).putInt(mem.localOffset);
            out.writeBytes(h.array());
            out.writeBytes(mem.name);
        }
        int cdSize = out.size() - cdStart;
        ByteBuffer end = buffer(EOCD_SIZE);
        end.putInt(EOCD_SIG).putShort((short) 0).putShort((short) 0).putShort((short) members.size())
            .putShort((short) members.size()).putInt(cdSize).putInt(cdStart).putShort((short) cm.length);
        out.writeBytes(end.array());
        out.writeBytes(cm);
        return out.toByteArray();
    }

    public static byte[] write(List<ArchiveEntry> entries, Method method) {
        return write(entries, method, "");
    }

    private static int version(int method) {
        return method == 8 ? 20 : 10;
    }

    private static ByteBuffer buffer(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!d.finished()) {
                o.write(buf, 0, d.deflate(buf));
            }
            return o.toByteArray();
        } finally {
            d.end();
        }
    }
}
