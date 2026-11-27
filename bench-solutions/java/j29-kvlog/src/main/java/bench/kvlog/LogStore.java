package bench.kvlog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * Crash-safe append-only key-value log.
 *
 * <p>Record layout (big endian): type(1: 1=put, 2=delete) keyLen(4) valLen(4) key value crc32(4),
 * where the CRC covers every preceding byte of the record.
 */
public final class LogStore implements AutoCloseable {
    private static final String DATA = "data.log";
    private static final String TMP = "data.log.compact.tmp";
    private static final int MAX_KEY_CHARS = 1000;
    private static final int MAX_KEY_BYTES = MAX_KEY_CHARS * 3;
    private static final int MAX_VALUE = 1_048_576;
    private static final byte PUT = 1;
    private static final byte DEL = 2;
    private static final int HEADER = 9;

    private final Path dir;
    private final Path file;
    private final Map<String, byte[]> map = new TreeMap<>();
    private FileChannel ch;
    private long end;
    private boolean closed;

    private LogStore(Path dir) {
        this.dir = dir;
        this.file = dir.resolve(DATA);
    }

    public static LogStore open(Path dir) throws IOException {
        if (dir == null) throw new IllegalArgumentException("dir is null");
        Files.createDirectories(dir);
        LogStore s = new LogStore(dir);
        Files.deleteIfExists(dir.resolve(TMP));
        s.recover();
        return s;
    }

    private void recover() throws IOException {
        byte[] data = Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
        int pos = 0;
        CRC32 crc = new CRC32();
        while (true) {
            if (data.length - pos < HEADER) break;
            byte type = data[pos];
            int kl = readInt(data, pos + 1);
            int vl = readInt(data, pos + 5);
            if (type != PUT && type != DEL) break;
            if (kl < 1 || kl > MAX_KEY_BYTES || vl < 0 || vl > MAX_VALUE) break;
            if (type == DEL && vl != 0) break;
            long total = (long) HEADER + kl + vl + 4;
            if (data.length - pos < total) break;
            int bodyEnd = pos + HEADER + kl + vl;
            crc.reset();
            crc.update(data, pos, bodyEnd - pos);
            if ((int) crc.getValue() != readInt(data, bodyEnd)) break;
            String key = new String(data, pos + HEADER, kl, StandardCharsets.UTF_8);
            if (type == PUT) {
                byte[] v = new byte[vl];
                System.arraycopy(data, pos + HEADER + kl, v, 0, vl);
                map.put(key, v);
            } else {
                map.remove(key);
            }
            pos = bodyEnd + 4;
        }
        ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (pos < data.length) ch.truncate(pos);
        end = pos;
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static byte[] encode(byte type, byte[] key, byte[] value) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER + key.length + value.length + 4);
        bb.put(type).putInt(key.length).putInt(value.length).put(key).put(value);
        CRC32 crc = new CRC32();
        crc.update(bb.array(), 0, bb.position());
        bb.putInt((int) crc.getValue());
        return bb.array();
    }

    private static void checkKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("invalid key");
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("store is closed");
    }

    private void append(byte[] rec) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(rec);
        long p = end;
        try {
            while (bb.hasRemaining()) p += ch.write(bb, p);
        } catch (IOException e) {
            try {
                ch.truncate(end);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
        end = p;
    }

    public synchronized void put(String key, byte[] value) throws IOException {
        checkOpen();
        checkKey(key);
        if (value == null || value.length > MAX_VALUE) throw new IllegalArgumentException("invalid value");
        byte[] copy = value.clone();
        append(encode(PUT, key.getBytes(StandardCharsets.UTF_8), copy));
        map.put(key, copy);
    }

    public synchronized byte[] get(String key) {
        checkOpen();
        checkKey(key);
        byte[] v = map.get(key);
        return v == null ? null : v.clone();
    }

    public synchronized void delete(String key) throws IOException {
        checkOpen();
        checkKey(key);
        if (!map.containsKey(key)) return;
        append(encode(DEL, key.getBytes(StandardCharsets.UTF_8), new byte[0]));
        map.remove(key);
    }

    public synchronized int size() {
        checkOpen();
        return map.size();
    }

    public synchronized SortedSet<String> keys() {
        checkOpen();
        return Collections.unmodifiableSortedSet(new TreeSet<>(map.keySet()));
    }

    public synchronized void compact() throws IOException {
        checkOpen();
        Path tmp = dir.resolve(TMP);
        try {
            try (FileChannel t = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                long p = 0;
                for (Map.Entry<String, byte[]> e : map.entrySet()) {
                    ByteBuffer bb = ByteBuffer.wrap(encode(PUT, e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue()));
                    while (bb.hasRemaining()) p += t.write(bb, p);
                }
                t.force(false);
                newEnd = p;
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic move not supported", e);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        FileChannel old = ch;
        ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        end = newEnd;
        try {
            old.close();
        } catch (IOException ignored) {
            // old file already replaced
        }
    }

    private long newEnd;

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        ch.close();
    }
}
