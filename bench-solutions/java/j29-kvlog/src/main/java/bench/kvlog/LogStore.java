package bench.kvlog;

import java.io.IOException;
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
 * Append-only key-value log. Record layout (big endian):
 * type(1) keyLen(4) valueLen(4) key value crc32(4), the CRC covering all preceding bytes.
 */
public final class LogStore implements AutoCloseable {
    private static final byte PUT = 1;
    private static final byte DEL = 2;
    private static final int HEADER = 9;
    private static final int MAX_KEY_CHARS = 1000;
    private static final int MAX_KEY_BYTES = 4 * MAX_KEY_CHARS;
    private static final int MAX_VALUE = 1_048_576;

    private final Path dir;
    private final Path log;
    private final Path tmp;
    private final TreeMap<String, byte[]> map = new TreeMap<>();
    private FileChannel channel;
    private long end;
    private boolean closed;

    private LogStore(Path dir) {
        this.dir = dir;
        this.log = dir.resolve("data.log");
        this.tmp = dir.resolve("data.log.tmp");
    }

    public static LogStore open(Path dir) throws IOException {
        if (dir == null) {
            throw new IllegalArgumentException("dir is null");
        }
        Files.createDirectories(dir);
        LogStore s = new LogStore(dir);
        Files.deleteIfExists(s.tmp);
        s.channel = FileChannel.open(s.log, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            s.recover();
        } catch (IOException | RuntimeException e) {
            s.channel.close();
            throw e;
        }
        return s;
    }

    private void recover() throws IOException {
        long size = channel.size();
        byte[] data = new byte[(int) size];
        ByteBuffer bb = ByteBuffer.wrap(data);
        long pos = 0;
        while (bb.hasRemaining()) {
            int n = channel.read(bb, pos);
            if (n < 0) {
                break;
            }
            pos += n;
        }
        int good = 0;
        int off = 0;
        while (data.length - off >= HEADER + 4) {
            byte type = data[off];
            int kl = readInt(data, off + 1);
            int vl = readInt(data, off + 5);
            if ((type != PUT && type != DEL) || kl < 1 || kl > MAX_KEY_BYTES || vl < 0 || vl > MAX_VALUE
                    || (type == DEL && vl != 0)) {
                break;
            }
            long total = (long) HEADER + kl + vl + 4;
            if (total > data.length - off) {
                break;
            }
            int t = (int) total;
            CRC32 crc = new CRC32();
            crc.update(data, off, t - 4);
            if ((int) crc.getValue() != readInt(data, off + t - 4)) {
                break;
            }
            String key = new String(data, off + HEADER, kl, StandardCharsets.UTF_8);
            if (type == PUT) {
                byte[] v = new byte[vl];
                System.arraycopy(data, off + HEADER + kl, v, 0, vl);
                map.put(key, v);
            } else {
                map.remove(key);
            }
            off += t;
            good = off;
        }
        if (good < data.length) {
            channel.truncate(good);
        }
        end = good;
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static byte[] record(byte type, String key, byte[] value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        int total = HEADER + k.length + value.length + 4;
        ByteBuffer b = ByteBuffer.allocate(total);
        b.put(type).putInt(k.length).putInt(value.length).put(k).put(value);
        CRC32 crc = new CRC32();
        crc.update(b.array(), 0, total - 4);
        b.putInt((int) crc.getValue());
        return b.array();
    }

    private static void checkKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("invalid key");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("store is closed");
        }
    }

    private void append(byte[] rec) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(rec);
        long pos = end;
        try {
            while (bb.hasRemaining()) {
                pos += channel.write(bb, pos);
            }
        } catch (IOException e) {
            try {
                channel.truncate(end);
            } catch (IOException ignored) {
                // best effort; recovery will discard a partial record
            }
            throw e;
        }
        end = pos;
    }

    public synchronized void put(String key, byte[] value) throws IOException {
        ensureOpen();
        checkKey(key);
        if (value == null || value.length > MAX_VALUE) {
            throw new IllegalArgumentException("invalid value");
        }
        byte[] copy = value.clone();
        append(record(PUT, key, copy));
        map.put(key, copy);
    }

    public synchronized byte[] get(String key) {
        ensureOpen();
        checkKey(key);
        byte[] v = map.get(key);
        return v == null ? null : v.clone();
    }

    public synchronized void delete(String key) throws IOException {
        ensureOpen();
        checkKey(key);
        if (!map.containsKey(key)) {
            return;
        }
        append(record(DEL, key, new byte[0]));
        map.remove(key);
    }

    public synchronized int size() {
        ensureOpen();
        return map.size();
    }

    public synchronized SortedSet<String> keys() {
        ensureOpen();
        return Collections.unmodifiableSortedSet(new TreeSet<>(map.keySet()));
    }

    public synchronized void compact() throws IOException {
        ensureOpen();
        long written = 0;
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (Map.Entry<String, byte[]> e : map.entrySet()) {
                ByteBuffer bb = ByteBuffer.wrap(record(PUT, e.getKey(), e.getValue()));
                while (bb.hasRemaining()) {
                    written += out.write(bb);
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            Files.move(tmp, log, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        FileChannel old = channel;
        channel = FileChannel.open(log, StandardOpenOption.READ, StandardOpenOption.WRITE);
        end = written;
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
