package ae.build;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Persistent memory of earlier builds: per rule key the value, its hash and the dependencies (with
 * the dependency value hashes seen when the rule last ran).
 *
 * <p>File layout (big endian): {@code "AEBS"} magic, version byte, int record count, records
 * (key, value, hash, int dep count, deps (key, hash)) with strings as int length + UTF-8, then a
 * CRC32 of everything before it. Records are written in key order, so equal stores give equal bytes.
 */
public final class Store {
    /** Magic (4) + version (1) + record count (4). */
    static final int HEADER_SIZE = 9;
    /** CRC32 trailer. */
    static final int FOOTER_SIZE = 4;

    private static final byte[] MAGIC = {'A', 'E', 'B', 'S'};
    private static final byte VERSION = 1;

    /** A dependency together with the hash of its value when the owner last ran. */
    record Dep(String key, String hash) { }

    /** Read-only view of one stored record. */
    public record RecordView(String value, String hash, List<Dep> deps) {
        public List<String> depKeys() {
            return deps.stream().map(Dep::key).toList();
        }
    }

    private final Map<String, RecordView> records = new TreeMap<>();

    public RecordView record(String key) {
        return records.get(key);
    }

    public Set<String> keys() {
        return Set.copyOf(records.keySet());
    }

    public int size() {
        return records.size();
    }

    void put(String key, RecordView rec) {
        records.put(key, rec);
    }

    boolean remove(String key) {
        return records.remove(key) != null;
    }

    /** Writes the store via a temp file in the same directory and an atomic move. */
    public void save(Path file) throws IOException {
        byte[] bytes = encode();
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, ".store-", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Loads and validates a store; throws {@link CorruptStoreException} for any damage. */
    public static Store load(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    private byte[] encode() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.write(MAGIC);
        out.writeByte(VERSION);
        out.writeInt(records.size());
        for (Map.Entry<String, RecordView> e : records.entrySet()) {
            writeString(out, e.getKey());
            writeString(out, e.getValue().value());
            writeString(out, e.getValue().hash());
            out.writeInt(e.getValue().deps().size());
            for (Dep d : e.getValue().deps()) {
                writeString(out, d.key());
                writeString(out, d.hash());
            }
        }
        out.flush();
        return seal(bos.toByteArray());
    }

    /** Appends the CRC32 trailer to {@code body}. */
    static byte[] seal(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return ByteBuffer.allocate(body.length + FOOTER_SIZE).put(body).putInt((int) crc.getValue()).array();
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static Store decode(byte[] bytes) throws CorruptStoreException {
        if (bytes.length < HEADER_SIZE + FOOTER_SIZE) {
            throw new CorruptStoreException("file too short: " + bytes.length);
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - FOOTER_SIZE);
        int stored = ByteBuffer.wrap(bytes, bytes.length - FOOTER_SIZE, FOOTER_SIZE).getInt();
        if (stored != (int) crc.getValue()) {
            throw new CorruptStoreException("checksum mismatch");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes, 0, bytes.length - FOOTER_SIZE);
        try {
            byte[] magic = new byte[MAGIC.length];
            buf.get(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new CorruptStoreException("bad magic");
            }
            if (buf.get() != VERSION) {
                throw new CorruptStoreException("unsupported version");
            }
            int count = buf.getInt();
            // every record needs at least 4 ints of length/count fields
            if (count < 0 || (long) count * 16 > buf.remaining()) {
                throw new CorruptStoreException("implausible record count " + count);
            }
            Store store = new Store();
            String previous = null;
            for (int i = 0; i < count; i++) {
                String key = readString(buf);
                if (previous != null && key.compareTo(previous) <= 0) {
                    throw new CorruptStoreException("keys not strictly ascending at " + key);
                }
                previous = key;
                String value = readString(buf);
                String hash = readString(buf);
                int deps = buf.getInt();
                if (deps < 0 || (long) deps * 8 > buf.remaining()) {
                    throw new CorruptStoreException("implausible dep count " + deps);
                }
                List<Dep> list = new ArrayList<>(deps);
                for (int d = 0; d < deps; d++) {
                    list.add(new Dep(readString(buf), readString(buf)));
                }
                store.records.put(key, new RecordView(value, hash, List.copyOf(list)));
            }
            if (buf.hasRemaining()) {
                throw new CorruptStoreException("trailing bytes: " + buf.remaining());
            }
            return store;
        } catch (BufferUnderflowException e) {
            throw new CorruptStoreException("truncated record");
        }
    }

    private static String readString(ByteBuffer buf) throws CorruptStoreException {
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) {
            throw new CorruptStoreException("bad string length " + len);
        }
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "Store" + Objects.toString(records.keySet());
    }
}
