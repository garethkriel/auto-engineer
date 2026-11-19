package bench.kvlog;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * Crash-safe key-value store backed by an append-only log {@code dir/data.log}.
 *
 * <p>Record layout (big endian): {@code type(1) keyLen(4) valLen(4) key value crc32(4)};
 * the CRC covers every preceding byte of the record. Type 1 is put, type 2 is delete
 * (valLen is 0 for deletes).
 */
public final class LogStore implements AutoCloseable {
    static final String LOG_NAME = "data.log";
    static final String TMP_NAME = "data.log.compact.tmp";
    private static final byte PUT = 1;
    private static final byte DELETE = 2;
    private static final int HEADER = 9;
    private static final int CRC = 4;
    private static final int MAX_KEY_CHARS = 1000;
    private static final int MAX_KEY_BYTES = MAX_KEY_CHARS * 3;
    private static final int MAX_VALUE = 1_048_576;

    private final Path dir;
    private final Path log;
    private final TreeMap<String, byte[]> map = new TreeMap<>();
    private RandomAccessFile file;
    private boolean closed;

    private LogStore(Path dir) {
        this.dir = dir;
        this.log = dir.resolve(LOG_NAME);
    }

    public static LogStore open(Path dir) throws IOException {
        if (dir == null) {
            throw new IllegalArgumentException("dir is null");
        }
        Files.createDirectories(dir);
        LogStore s = new LogStore(dir);
        Files.deleteIfExists(dir.resolve(TMP_NAME));
        s.file = new RandomAccessFile(s.log.toFile(), "rw");
        try {
            s.recover();
        } catch (IOException | RuntimeException e) {
            s.file.close();
            throw e;
        }
        return s;
    }

    private void recover() throws IOException {
        long len = file.length();
        byte[] data = new byte[(int) Math.min(len, Integer.MAX_VALUE - 16)];
        if (len > data.length) {
            throw new IOException("log too large");
        }
        file.seek(0);
        file.readFully(data);
        ByteBuffer buf = ByteBuffer.wrap(data);
        int good = 0;
        CRC32 crc = new CRC32();
        while (true) {
            int p = good;
            if (data.length - p < HEADER) break;
            byte type = data[p];
            int kl = buf.getInt(p + 1);
            int vl = buf.getInt(p + 5);
            if (type != PUT && type != DELETE) break;
            if (kl < 1 || kl > MAX_KEY_BYTES || vl < 0 || vl > MAX_VALUE) break;
            if (type == DELETE && vl != 0) break;
            long total = (long) HEADER + kl + vl + CRC;
            if (total > data.length - p) break;
            crc.reset();
            crc.update(data, p, HEADER + kl + vl);
            if ((int) crc.getValue() != buf.getInt(p + HEADER + kl + vl)) break;
            String key = new String(data, p + HEADER, kl, StandardCharsets.UTF_8);
            if (type == PUT) {
                byte[] v = new byte[vl];
                System.arraycopy(data, p + HEADER + kl, v, 0, vl);
                map.put(key, v);
            } else {
                map.remove(key);
            }
            good = p + (int) total;
        }
        if (good != len) {
            file.setLength(good);
        }
        file.seek(good);
    }

    private static byte[] encode(byte type, String key, byte[] value) {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        int vl = value == null ? 0 : value.length;
        ByteBuffer b = ByteBuffer.allocate(HEADER + kb.length + vl + CRC);
        b.put(type).putInt(kb.length).putInt(vl).put(kb);
        if (value != null) b.put(value);
        CRC32 crc = new CRC32();
        crc.update(b.array(), 0, b.position());
        b.putInt((int) crc.getValue());
        return b.array();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("store is closed");
        }
    }

    private static void checkKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("invalid key");
        }
    }

    private void append(byte[] rec) throws IOException {
        long pos = file.getFilePointer();
        try {
            file.write(rec);
        } catch (IOException e) {
            try {
                file.setLength(pos);
                file.seek(pos);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
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
        append(encode(DELETE, key, null));
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
        Path tmp = dir.resolve(TMP_NAME);
        try {
            try (RandomAccessFile out = new RandomAccessFile(tmp.toFile(), "rw")) {
                out.setLength(0);
                for (var e : map.entrySet()) {
                    out.write(encode(PUT, e.getKey(), e.getValue()));
                }
            }
            file.close();
            Files.move(tmp, log, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            reopenQuietly();
            throw e;
        }
        file = new RandomAccessFile(log.toFile(), "rw");
        file.seek(file.length());
    }

    private void reopenQuietly() {
        try {
            if (file.getChannel().isOpen()) {
                return;
            }
            file = new RandomAccessFile(log.toFile(), "rw");
            file.seek(file.length());
        } catch (IOException ignored) {
            // keep old handle
        }
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
