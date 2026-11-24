package bench.kvlog;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
 * Crash-safe key-value store backed by an append-only log.
 *
 * <p>Record layout (big endian): crc32 (4) | type (1) | keyLen (4) | valLen (4) | key | value.
 * The checksum covers everything after the checksum field.
 */
public final class LogStore implements AutoCloseable {
    private static final String LOG = "data.log";
    private static final String TMP = "data.log.tmp";
    private static final int MAX_KEY_CHARS = 1000;
    private static final int MAX_KEY_BYTES = 4 * MAX_KEY_CHARS;
    private static final int MAX_VALUE = 1_048_576;
    private static final int HEADER = 13;
    private static final byte PUT = 1;
    private static final byte DEL = 2;

    private final Path dir;
    private final Path log;
    private final TreeMap<String, byte[]> map = new TreeMap<>();
    private RandomAccessFile file;
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
        RandomAccessFile raf = new RandomAccessFile(log.toFile(), "rw");
        try {
            byte[] data = new byte[(int) raf.length()];
            raf.readFully(data);
            int pos = 0;
            while (true) {
                int next = parseRecord(data, pos);
                if (next < 0) {
                    break;
                }
                pos = next;
            }
            if (pos < data.length) {
                raf.setLength(pos);
            }
            raf.seek(pos);
            file = raf;
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    /** Applies the record at pos to the map; returns the end offset or -1 if invalid/incomplete. */
    private int parseRecord(byte[] d, int pos) {
        if (d.length - pos < HEADER) {
            return -1;
        }
        ByteBuffer bb = ByteBuffer.wrap(d, pos, HEADER);
        int crc = bb.getInt();
        byte type = bb.get();
        int kl = bb.getInt();
        int vl = bb.getInt();
        if ((type != PUT && type != DEL) || kl < 1 || kl > MAX_KEY_BYTES || vl < 0 || vl > MAX_VALUE
                || (type == DEL && vl != 0)) {
            return -1;
        }
        long end = (long) pos + HEADER + kl + vl;
        if (end > d.length) {
            return -1;
        }
        CRC32 c = new CRC32();
        c.update(d, pos + 4, HEADER - 4 + kl + vl);
        if ((int) c.getValue() != crc) {
            return -1;
        }
        String key = new String(d, pos + HEADER, kl, StandardCharsets.UTF_8);
        if (type == PUT) {
            byte[] v = new byte[vl];
            System.arraycopy(d, pos + HEADER + kl, v, 0, vl);
            map.put(key, v);
        } else {
            map.remove(key);
        }
        return (int) end;
    }

    private static byte[] encode(byte type, byte[] key, byte[] value) {
        byte[] rec = new byte[HEADER + key.length + value.length];
        ByteBuffer bb = ByteBuffer.wrap(rec);
        bb.putInt(0);
        bb.put(type);
        bb.putInt(key.length);
        bb.putInt(value.length);
        bb.put(key);
        bb.put(value);
        CRC32 c = new CRC32();
        c.update(rec, 4, rec.length - 4);
        bb.putInt(0, (int) c.getValue());
        return rec;
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

    public synchronized void put(String key, byte[] value) throws IOException {
        checkOpen();
        checkKey(key);
        if (value == null || value.length > MAX_VALUE) {
            throw new IllegalArgumentException("invalid value");
        }
        byte[] copy = value.clone();
        file.write(encode(PUT, key.getBytes(StandardCharsets.UTF_8), copy));
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
        if (!map.containsKey(key)) {
            return;
        }
        file.write(encode(DEL, key.getBytes(StandardCharsets.UTF_8), new byte[0]));
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
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(1 << 16);
                for (Map.Entry<String, byte[]> e : map.entrySet()) {
                    buf.write(encode(PUT, e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue()));
                    if (buf.size() >= (1 << 20)) {
                        ch.write(ByteBuffer.wrap(buf.toByteArray()));
                        buf.reset();
                    }
                }
                if (buf.size() > 0) {
                    ch.write(ByteBuffer.wrap(buf.toByteArray()));
                }
                ch.force(true);
            }
            Files.move(tmp, log, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
        RandomAccessFile old = file;
        RandomAccessFile fresh = new RandomAccessFile(log.toFile(), "rw");
        fresh.seek(fresh.length());
        file = fresh;
        old.close();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        file.close();
    }
}
