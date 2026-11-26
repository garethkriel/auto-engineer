package bench.kvlog;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.TreeMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * Append-only key-value log with CRC-protected records.
 *
 * <p>Record layout (big endian): type(1) keyLen(4) valLen(4) key value crc32(4), where the CRC
 * covers every preceding byte of the record.
 */
public final class LogStore implements AutoCloseable {
    private static final int HEADER = 9;
    private static final int CRC = 4;
    private static final byte PUT = 1;
    private static final byte DEL = 2;
    private static final int MAX_KEY_CHARS = 1000;
    private static final int MAX_KEY_BYTES = 4 * MAX_KEY_CHARS;
    private static final int MAX_VALUE = 1_048_576;

    private final Path dir;
    private final Path file;
    private final Path tmp;
    private final TreeMap<String, byte[]> map = new TreeMap<>();
    private FileChannel channel;
    private long end;
    private boolean closed;

    private LogStore(Path dir) {
        this.dir = dir;
        this.file = dir.resolve("data.log");
        this.tmp = dir.resolve("data.log.tmp");
    }

    public static LogStore open(Path dir) throws IOException {
        if (dir == null) {
            throw new IllegalArgumentException("dir is null");
        }
        Files.createDirectories(dir);
        LogStore s = new LogStore(dir);
        Files.deleteIfExists(s.tmp);
        s.recover();
        return s;
    }

    private void recover() throws IOException {
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long size = channel.size();
        if (size > Integer.MAX_VALUE - 16) {
            throw new IOException("log too large");
        }
        ByteBuffer all = ByteBuffer.allocate((int) size);
        while (all.hasRemaining()) {
            if (channel.read(all, all.position()) < 0) break;
        }
        byte[] data = all.array();
        int len = all.position();
        int pos = 0;
        CRC32 crc = new CRC32();
        while (true) {
            if (len - pos < HEADER) break;
            ByteBuffer b = ByteBuffer.wrap(data, pos, len - pos);
            byte type = b.get();
            int kl = b.getInt();
            int vl = b.getInt();
            if ((type != PUT && type != DEL) || kl < 1 || kl > MAX_KEY_BYTES || vl < 0 || vl > MAX_VALUE
                    || (type == DEL && vl != 0)) {
                break;
            }
            long total = (long) HEADER + kl + vl + CRC;
            if (total > len - pos) break;
            crc.reset();
            crc.update(data, pos, HEADER + kl + vl);
            int stored = ByteBuffer.wrap(data, pos + HEADER + kl + vl, CRC).getInt();
            if ((int) crc.getValue() != stored) break;
            String key = new String(data, pos + HEADER, kl, StandardCharsets.UTF_8);
            if (type == PUT) {
                byte[] v = new byte[vl];
                System.arraycopy(data, pos + HEADER + kl, v, 0, vl);
                map.put(key, v);
            } else {
                map.remove(key);
            }
            pos += (int) total;
        }
        if (pos < size) {
            channel.truncate(pos);
        }
        end = pos;
    }

    private static byte[] encode(byte type, byte[] key, byte[] value) {
        ByteBuffer b = ByteBuffer.allocate(HEADER + key.length + value.length + CRC);
        b.put(type).putInt(key.length).putInt(value.length).put(key).put(value);
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
        ByteBuffer b = ByteBuffer.wrap(rec);
        long p = end;
        while (b.hasRemaining()) {
            p += channel.write(b, p);
        }
        end = p;
    }

    public synchronized void put(String key, byte[] value) throws IOException {
        checkOpen();
        checkKey(key);
        if (value == null || value.length > MAX_VALUE) {
            throw new IllegalArgumentException("invalid value");
        }
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
        if (!map.containsKey(key)) {
            return;
        }
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
        long newEnd = 0;
        try (OutputStream out = new BufferedOutputStream(
                Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE), 1 << 16)) {
            for (var e : map.entrySet()) {
                byte[] rec = encode(PUT, e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue());
                out.write(rec);
                newEnd += rec.length;
            }
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        FileChannel old = channel;
        channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        end = newEnd;
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
