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
 * Append-only key-value log. Record layout (big endian):
 * type(1) keyLen(4) valLen(4) key value crc32(4), the CRC covering every
 * preceding byte of the record. Type 1 = put, 2 = delete (valLen 0).
 */
public final class LogStore implements AutoCloseable {
    private static final String LOG = "data.log";
    private static final String TMP = "data.log.tmp";
    private static final int MAX_KEY_CHARS = 1000;
    private static final int MAX_KEY_BYTES = 4 * MAX_KEY_CHARS;
    private static final int MAX_VALUE = 1_048_576;
    private static final int HEADER = 9;
    private static final byte PUT = 1;
    private static final byte DEL = 2;

    private final Path dir;
    private final Path log;
    private final TreeMap<String, byte[]> map = new TreeMap<>();
    private FileChannel channel;
    private long end;
    private boolean closed;

    private LogStore(Path dir) {
        this.dir = dir;
        this.log = dir.resolve(LOG);
    }

    public static LogStore open(Path dir) throws IOException {
        if (dir == null) {
            throw new IllegalArgumentException("dir is null");
        }
        Files.createDirectories(dir);
        LogStore s = new LogStore(dir);
        Files.deleteIfExists(dir.resolve(TMP));
        s.recover();
        return s;
    }

    private void recover() throws IOException {
        channel = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.READ,
            StandardOpenOption.WRITE);
        try {
            byte[] data = Files.readAllBytes(log);
            int pos = 0;
            while (true) {
                int next = parseRecord(data, pos);
                if (next < 0) {
                    break;
                }
                pos = next;
            }
            if (pos < data.length) {
                channel.truncate(pos);
            }
            end = pos;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** Applies the record at pos to the map; returns the next offset or -1 if invalid/incomplete. */
    private int parseRecord(byte[] d, int pos) {
        if (d.length - pos < HEADER) {
            return -1;
        }
        ByteBuffer b = ByteBuffer.wrap(d, pos, HEADER);
        byte type = b.get();
        int kl = b.getInt();
        int vl = b.getInt();
        if ((type != PUT && type != DEL) || kl < 1 || kl > MAX_KEY_BYTES || vl < 0 || vl > MAX_VALUE
            || (type == DEL && vl != 0)) {
            return -1;
        }
        long total = (long) HEADER + kl + vl + 4;
        if (d.length - pos < total) {
            return -1;
        }
        int bodyEnd = pos + HEADER + kl + vl;
        CRC32 crc = new CRC32();
        crc.update(d, pos, bodyEnd - pos);
        int stored = ByteBuffer.wrap(d, bodyEnd, 4).getInt();
        if ((int) crc.getValue() != stored) {
            return -1;
        }
        String key = new String(d, pos + HEADER, kl, StandardCharsets.UTF_8);
        if (key.isEmpty() || key.length() > MAX_KEY_CHARS) {
            return -1;
        }
        if (type == PUT) {
            byte[] v = new byte[vl];
            System.arraycopy(d, pos + HEADER + kl, v, 0, vl);
            map.put(key, v);
        } else {
            map.remove(key);
        }
        return bodyEnd + 4;
    }

    private static byte[] encode(byte type, String key, byte[] value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        int vl = value == null ? 0 : value.length;
        ByteBuffer b = ByteBuffer.allocate(HEADER + k.length + vl + 4);
        b.put(type).putInt(k.length).putInt(vl).put(k);
        if (vl > 0) {
            b.put(value);
        }
        CRC32 crc = new CRC32();
        crc.update(b.array(), 0, b.position());
        b.putInt((int) crc.getValue());
        return b.array();
    }

    private static void checkKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("invalid key");
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("store is closed");
        }
    }

    private void append(byte[] rec) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(rec);
        try {
            long p = end;
            while (buf.hasRemaining()) {
                p += channel.write(buf, p);
            }
        } catch (IOException e) {
            try {
                channel.truncate(end);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
        end += rec.length;
    }

    public synchronized void put(String key, byte[] value) throws IOException {
        checkOpen();
        checkKey(key);
        if (value == null || value.length > MAX_VALUE) {
            throw new IllegalArgumentException("invalid value");
        }
        byte[] copy = value.clone();
        append(encode(PUT, key, copy));
        map.put(key, copy);
    }

    public synchronized byte[] get(String key) {
        checkOpen();
        byte[] v = key == null ? null : map.get(key);
        return v == null ? null : v.clone();
    }

    public synchronized void delete(String key) throws IOException {
        checkOpen();
        checkKey(key);
        if (!map.containsKey(key)) {
            return;
        }
        append(encode(DEL, key, null));
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
        long size = 0;
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (Map.Entry<String, byte[]> e : map.entrySet()) {
                ByteBuffer b = ByteBuffer.wrap(encode(PUT, e.getKey(), e.getValue()));
                while (b.hasRemaining()) {
                    out.write(b);
                }
                size += b.capacity();
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            try {
                Files.move(tmp, log, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, log, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        FileChannel old = channel;
        channel = FileChannel.open(log, StandardOpenOption.READ, StandardOpenOption.WRITE);
        end = size;
        old.close();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        channel.close();
    }
}
